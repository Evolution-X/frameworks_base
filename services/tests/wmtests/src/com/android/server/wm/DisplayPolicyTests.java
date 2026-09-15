/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.internal.perfetto.protos.Windowmanagerservice.DisplayContentProto.DISPLAY_POLICY;
import static android.server.wm.ProtoExtractors.extract;
import static android.view.DisplayCutout.NO_CUTOUT;
import static android.view.InsetsSource.ID_IME;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemGestures;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.RENDERING_HINT_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.Presubmit;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.Test;
import org.junit.runner.RunWith;

import perfetto.protos.Windowmanagerservice;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayPolicyTests extends WindowTestsBase {
    private WindowState createOpaqueFullscreen(boolean hasLightNavBar) {
        final WindowState win = newWindowBuilder("opaqueFullscreen", TYPE_BASE_APPLICATION).build();
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        attrs.format = PixelFormat.OPAQUE;
        attrs.insetsFlags.appearance = hasLightNavBar ? APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        return win;
    }

    private WindowState createDreamWindow() {
        final WindowState win = createDreamWindow("dream", TYPE_BASE_APPLICATION);
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        attrs.format = PixelFormat.OPAQUE;
        return win;
    }

    private WindowState createDimmingDialogWindow(boolean canBeImTarget) {
        final WindowState win = spy(newWindowBuilder("dimmingDialog", TYPE_APPLICATION).build());
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = WRAP_CONTENT;
        attrs.height = WRAP_CONTENT;
        attrs.flags = FLAG_DIM_BEHIND | (canBeImTarget ? 0 : FLAG_ALT_FOCUSABLE_IM);
        attrs.format = PixelFormat.TRANSLUCENT;
        when(win.isDimming()).thenReturn(true);
        return win;
    }

    private WindowState createInputMethodWindow(boolean visible, boolean drawNavBar,
            boolean hasLightNavBar) {
        final WindowState win = newWindowBuilder("inputMethod", TYPE_INPUT_METHOD).build();
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN
                | (drawNavBar ? FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS : 0);
        attrs.format = PixelFormat.TRANSPARENT;
        attrs.insetsFlags.appearance = hasLightNavBar ? APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        win.mHasSurface = visible;
        final var imeToken = win.mToken.asImeToken();
        if (android.view.inputmethod.Flags.warmWorkProfileIme() && imeToken != null) {
            imeToken.setClientVisible(visible);
        }
        return win;
    }

    @Test
    public void testChooseNavigationColorWindowLw() {
        final WindowState candidate = createOpaqueFullscreen(false);
        final WindowState dimmingImTarget = createDimmingDialogWindow(true);
        final WindowState dimmingNonImTarget = createDimmingDialogWindow(false);

        final WindowState visibleIme = createInputMethodWindow(true, true, false);
        final WindowState invisibleIme = createInputMethodWindow(false, true, false);
        final WindowState imeNonDrawNavBar = createInputMethodWindow(true, false, false);

        // If everything is null, return null.
        assertNull(null, DisplayPolicy.chooseNavigationColorWindowLw(
                null, null, true));

        // If no IME windows, return candidate window.
        assertEquals(candidate, DisplayPolicy.chooseNavigationColorWindowLw(
                candidate, null, true));
        assertEquals(dimmingImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingImTarget, null, true));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingNonImTarget, null, true));

        // If IME is not visible, return candidate window.
        assertEquals(null, DisplayPolicy.chooseNavigationColorWindowLw(
                null, invisibleIme, true));
        assertEquals(candidate, DisplayPolicy.chooseNavigationColorWindowLw(
                candidate, invisibleIme, true));
        assertEquals(dimmingImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingImTarget, invisibleIme, true));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingNonImTarget, invisibleIme, true));

        // If IME is visible, return candidate when the candidate window is not dimming.
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                null, visibleIme, true));
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                candidate, visibleIme, true));

        // If IME is visible and the candidate window is dimming, checks whether the dimming window
        // can be IME tartget or not.
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingImTarget, visibleIme, true));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingNonImTarget, visibleIme, true));

        // Only IME windows that have FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS should be navigation color
        // window.
        assertEquals(null, DisplayPolicy.chooseNavigationColorWindowLw(
                null, imeNonDrawNavBar, true));
        assertEquals(candidate, DisplayPolicy.chooseNavigationColorWindowLw(
                candidate, imeNonDrawNavBar, true));
        assertEquals(dimmingImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingImTarget, imeNonDrawNavBar, true));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                dimmingNonImTarget, imeNonDrawNavBar, true));
    }

    @Test
    public void testChooseNavigationBackgroundWindow() {
        final WindowState drawBarWin = createOpaqueFullscreen(false);
        final WindowState nonDrawBarWin = createDimmingDialogWindow(true);

        final WindowState visibleIme = createInputMethodWindow(true, true, false);
        final WindowState invisibleIme = createInputMethodWindow(false, true, false);
        final WindowState nonDrawBarIme = createInputMethodWindow(true, false, false);

        assertEquals(drawBarWin, DisplayPolicy.chooseNavigationBackgroundWindow(
                drawBarWin, null, true));
        assertNull(DisplayPolicy.chooseNavigationBackgroundWindow(
                null, null, true));
        assertNull(DisplayPolicy.chooseNavigationBackgroundWindow(
                nonDrawBarWin, null, true));

        assertEquals(visibleIme, DisplayPolicy.chooseNavigationBackgroundWindow(
                drawBarWin, visibleIme, true));
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationBackgroundWindow(
                null, visibleIme, true));
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationBackgroundWindow(
                nonDrawBarWin, visibleIme, true));

        assertEquals(drawBarWin, DisplayPolicy.chooseNavigationBackgroundWindow(
                drawBarWin, invisibleIme, true));
        assertNull(DisplayPolicy.chooseNavigationBackgroundWindow(
                null, invisibleIme, true));
        assertNull(DisplayPolicy.chooseNavigationBackgroundWindow(
                nonDrawBarWin, invisibleIme, true));

        assertEquals(drawBarWin, DisplayPolicy.chooseNavigationBackgroundWindow(
                drawBarWin, nonDrawBarIme, true));
        assertNull(DisplayPolicy.chooseNavigationBackgroundWindow(
                null, nonDrawBarIme, true));
        assertNull(DisplayPolicy.chooseNavigationBackgroundWindow(
                nonDrawBarWin, nonDrawBarIme, true));
    }

    @SetupWindows(addWindows = W_NAVIGATION_BAR)
    @Test
    public void testUpdateLightNavigationBarLw() {
        DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final WindowState opaqueDarkNavBar = createOpaqueFullscreen(false);
        final WindowState opaqueLightNavBar = createOpaqueFullscreen(true);

        final WindowState dimming = createDimmingDialogWindow(false);

        final WindowState imeDrawDarkNavBar = createInputMethodWindow(true, true, false);
        final WindowState imeDrawLightNavBar = createInputMethodWindow(true, true, true);

        mDisplayContent.setLayoutNeeded();
        mDisplayContent.performLayout(true /* initial */, false /* updateImeWindows */);

        final InsetsSource navSource = new InsetsSource(
                InsetsSource.createId(null, 0, navigationBars()), navigationBars());
        navSource.setFrame(mNavBarWindow.getFrame());
        opaqueDarkNavBar.mAboveInsetsState.addSource(navSource);
        opaqueLightNavBar.mAboveInsetsState.addSource(navSource);
        dimming.mAboveInsetsState.addSource(navSource);
        imeDrawDarkNavBar.mAboveInsetsState.addSource(navSource);
        imeDrawLightNavBar.mAboveInsetsState.addSource(navSource);

        // If there is no window, APPEARANCE_LIGHT_NAVIGATION_BARS is not allowed.
        assertEquals(0,
                displayPolicy.updateLightNavigationBarLw(APPEARANCE_LIGHT_NAVIGATION_BARS, null));

        // Control window overrides APPEARANCE_LIGHT_NAVIGATION_BARS flag.
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(0, opaqueDarkNavBar));
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueDarkNavBar));
        assertEquals(APPEARANCE_LIGHT_NAVIGATION_BARS, displayPolicy.updateLightNavigationBarLw(
                0, opaqueLightNavBar));
        assertEquals(APPEARANCE_LIGHT_NAVIGATION_BARS, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueLightNavBar));
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @Test
    public void testComputeTopFullscreenOpaqueWindow() {
        final WindowManager.LayoutParams attrs = mAppWindow.mAttrs;
        attrs.x = attrs.y = 0;
        attrs.height = attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        policy.addWindowLw(mStatusBarWindow, mStatusBarWindow.mAttrs);

        policy.applyPostLayoutPolicyLw(
                mAppWindow, attrs, null /* attached */, null /* imeTarget */);

        assertEquals(mAppWindow, policy.getTopFullscreenOpaqueWindow());
    }

    @SetupWindows(addWindows = W_NOTIFICATION_SHADE)
    @Test
    public void testVisibleProcessWhileDozing() {
        final WindowProcessController wpc = mNotificationShadeWindow.getProcess();
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        policy.addWindowLw(mNotificationShadeWindow, mNotificationShadeWindow.mAttrs);

        policy.screenTurnedOff(false /* acquireSleepToken */);
        policy.setAwake(false);
        policy.screenTurningOn(null /* screenOnListener */);
        assertTrue(wpc.isShowingUiWhileDozing());
        policy.screenTurnedOff(false /* acquireSleepToken */);
        assertFalse(wpc.isShowingUiWhileDozing());

        policy.screenTurningOn(null /* screenOnListener */);
        assertTrue(wpc.isShowingUiWhileDozing());
        policy.setAwake(true);
        assertFalse(wpc.isShowingUiWhileDozing());
    }

    @Test
    public void testNonSystemToastAnimation() {
        final WindowState win = newWindowBuilder("Toast",
                WindowManager.LayoutParams.TYPE_TOAST).build();
        win.mAttrs.windowAnimations = android.R.style.Animation_InputMethod;
        setFieldValue(win.mSession, "mCanAddInternalSystemWindow", false);
        mDisplayContent.getDisplayPolicy().adjustWindowParamsLw(win, win.mAttrs);

        assertEquals(android.R.style.Animation_Toast, win.mAttrs.windowAnimations);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMainAppWindowDisallowFitSystemWindowTypes() {
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        final WindowState activity = createBaseApplicationWindow();
        activity.mAttrs.renderingHints |= RENDERING_HINT_FORCE_DRAW_BAR_BACKGROUNDS;

        policy.adjustWindowParamsLw(activity, activity.mAttrs);
    }

    private WindowState createApplicationWindow() {
        final WindowState win = newWindowBuilder("Application", TYPE_APPLICATION).build();
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags = FLAG_SHOW_WHEN_LOCKED | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        attrs.format = PixelFormat.OPAQUE;
        win.mHasSurface = true;
        return win;
    }

    private WindowState createBaseApplicationWindow() {
        final WindowState win = newWindowBuilder("Application", TYPE_BASE_APPLICATION).build();
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        attrs.format = PixelFormat.OPAQUE;
        win.mHasSurface = true;
        return win;
    }

    @Test
    public void testOverlappingWithNavBar() {
        final InsetsSource navSource = new InsetsSource(
                InsetsSource.createId(null, 0, navigationBars()), navigationBars());
        navSource.setFrame(new Rect(100, 200, 200, 300));
        testOverlappingWithNavBarType(navSource);
    }

    @Test
    public void testOverlappingWithExtraNavBar() {
        final InsetsSource navSource = new InsetsSource(
                InsetsSource.createId(null, 1, navigationBars()), navigationBars());
        navSource.setFrame(new Rect(100, 200, 200, 300));
        testOverlappingWithNavBarType(navSource);
    }

    private void testOverlappingWithNavBarType(InsetsSource navSource) {
        final WindowState targetWin = createApplicationWindow();
        final WindowFrames winFrame = targetWin.getWindowFrames();
        winFrame.mFrame.set(new Rect(100, 100, 200, 200));
        targetWin.mAboveInsetsState.addSource(navSource);

        assertFalse("Freeform is overlapping with navigation bar",
                DisplayPolicy.isOverlappingWithNavBar(targetWin));

        winFrame.mFrame.set(new Rect(100, 101, 200, 201));
        assertTrue("Freeform should be overlapping with navigation bar (bottom)",
                DisplayPolicy.isOverlappingWithNavBar(targetWin));

        winFrame.mFrame.set(new Rect(99, 200, 199, 300));
        assertTrue("Freeform should be overlapping with navigation bar (right)",
                DisplayPolicy.isOverlappingWithNavBar(targetWin));

        winFrame.mFrame.set(new Rect(199, 200, 299, 300));
        assertTrue("Freeform should be overlapping with navigation bar (left)",
                DisplayPolicy.isOverlappingWithNavBar(targetWin));
    }

    @Test
    public void testSwitchDecorInsets() {
        final WindowState win = createApplicationWindow();
        final WindowState bar = createNavBarWithProvidedInsets(mDisplayContent);
        bar.getFrame().set(0, mDisplayContent.mDisplayFrames.mHeight - NAV_BAR_HEIGHT,
                mDisplayContent.mDisplayFrames.mWidth, mDisplayContent.mDisplayFrames.mHeight);
        final int insetsId = bar.mAttrs.providedInsets[0].getId();
        final InsetsSourceProvider provider = mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(insetsId, bar.mAttrs.providedInsets[0].getType());
        provider.setServerVisible(true);
        provider.updateSourceFrame(bar.getFrame());

        final InsetsSource prevInsetsSource = new InsetsSource(provider.getSource());
        // Assume that the insets provider is temporarily invisible during switching.
        provider.getSource().setVisible(false);

        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final DisplayInfo info = mDisplayContent.getDisplayInfo();
        final int w = info.logicalWidth;
        final int h = info.logicalHeight;
        displayPolicy.updateDecorInsetsInfo();
        final Rect prevConfigFrame = new Rect(displayPolicy.getDecorInsetsInfo(info.rotation,
                info.logicalWidth, info.logicalHeight).mOverrideConfigFrame);

        displayPolicy.updateCachedDecorInsets();
        mDisplayContent.updateBaseDisplayMetrics(w / 2, h / 2,
                info.logicalDensityDpi, info.physicalXDpi, info.physicalYDpi);
        // There is no previous cache. But the current state will be cached.
        assertFalse(displayPolicy.shouldKeepCurrentDecorInsets());

        // Switch to original state.
        displayPolicy.updateCachedDecorInsets();
        mDisplayContent.updateBaseDisplayMetrics(w, h,
                info.logicalDensityDpi, info.physicalXDpi, info.physicalYDpi);
        assertTrue(displayPolicy.shouldKeepCurrentDecorInsets());
        // The current insets are restored from cache directly.
        assertEquals(prevConfigFrame, displayPolicy.getDecorInsetsInfo(info.rotation,
                info.logicalWidth, info.logicalHeight).mOverrideConfigFrame);
        // Assume that the InsetsSource in current InsetsState is not updated yet. And it will be
        // replaced by the one in cache.
        InsetsState currentInsetsState = new InsetsState();
        final InsetsSource currentSource = new InsetsSource(provider.getSource());
        currentSource.setVisible(true);
        currentSource.getFrame().scale(0.5f);
        currentInsetsState.addSource(currentSource);
        currentInsetsState = mDisplayContent.getInsetsPolicy().adjustInsetsForWindow(
                win, currentInsetsState);
        final InsetsSource adjustedSource = currentInsetsState.peekSource(insetsId);
        assertNotNull(adjustedSource);
        // The frame is restored from previous state, but the visibility still uses current state.
        assertEquals(prevInsetsSource.getFrame(), adjustedSource.getFrame());
        assertTrue(adjustedSource.isVisible());

        // If screen is not fully turned on, then the cache should be preserved.
        displayPolicy.screenTurnedOff(false /* acquireSleepToken */);
        final TransitionController transitionController = mDisplayContent.mTransitionController;
        spyOn(transitionController);
        doReturn(true).when(transitionController).isCollecting();
        doReturn(Integer.MAX_VALUE).when(transitionController).getCollectingTransitionId();
        // Make CachedDecorInsets.canPreserve return false.
        displayPolicy.physicalDisplayUpdated();
        assertFalse(displayPolicy.shouldKeepCurrentDecorInsets());
        displayPolicy.getDecorInsetsInfo(info.rotation, info.logicalWidth, info.logicalHeight)
                .mOverrideConfigFrame.offset(1, 1);
        // Even if CachedDecorInsets.canPreserve returns false, the cache won't be cleared.
        displayPolicy.updateDecorInsetsInfo();
        // Successful to restore from cache.
        displayPolicy.updateCachedDecorInsets();
        assertEquals(prevConfigFrame, displayPolicy.getDecorInsetsInfo(info.rotation,
                info.logicalWidth, info.logicalHeight).mOverrideConfigFrame);
    }

    @Test
    public void testUpdateDisplayConfigurationByDecor() {
        doReturn(NO_CUTOUT).when(mDisplayContent).calculateDisplayCutoutForRotation(anyInt());
        final WindowState navbar = createNavBarWithProvidedInsets(mDisplayContent);
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final DisplayInfo di = mDisplayContent.getDisplayInfo();
        // No configuration update when flag enables.
        assertFalse(displayPolicy.updateDecorInsetsInfo());
        assertEquals(NAV_BAR_HEIGHT, displayPolicy.getDecorInsetsInfo(di.rotation,
                di.logicalHeight, di.logicalWidth).mOverrideConfigInsets.bottom);

        final int barHeight = 2 * NAV_BAR_HEIGHT;
        navbar.mAttrs.providedInsets[0].setInsetsSize(Insets.of(0, 0, 0, barHeight));
        assertFalse(displayPolicy.updateDecorInsetsInfo());
        assertEquals(barHeight, displayPolicy.getDecorInsetsInfo(di.rotation,
                di.logicalHeight, di.logicalWidth).mOverrideConfigInsets.bottom);
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testImeInsetsGivenContentFrame() {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();

        mDisplayContent.setImeWindow(mImeWindow);
        makeWindowVisible(mImeWindow);
        mImeWindow.getControllableInsetProvider().setServerVisible(true);

        mImeWindow.mGivenContentInsets.set(0, 10, 0, 0);

        displayPolicy.layoutWindowLw(mImeWindow, null, mDisplayContent.mDisplayFrames);
        final InsetsState state = mDisplayContent.getInsetsStateController().getRawInsetsState();
        final InsetsSource imeSource = state.peekSource(ID_IME);

        assertNotNull(imeSource);
        assertFalse(imeSource.getFrame().isEmpty());
        assertEquals(mImeWindow.getWindowFrames().mFrame.height() - 10,
                imeSource.getFrame().height());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testStatusBarTouchTransfer_usesControlWindow() {
        final InsetsControlTarget target = mock(InsetsControlTarget.class);
        doReturn(mAppWindow).when(target).getWindow();
        mAppWindow.mInputChannelToken = new Binder();
        final DisplayPolicy policy = prepareStatusBarTouchTransfer(target);
        doReturn(true).when(mWm.mInputManager).transferTouchGesture(
                mAppWindow.mInputChannelToken, mStatusBarWindow.mInputChannelToken, false);

        policy.requestTransientBars(mStatusBarWindow, true);

        verify(target).showInsets(statusBars() | navigationBars(), null);
        verify(mWm.mInputManager).transferTouchGesture(mAppWindow.mInputChannelToken,
                mStatusBarWindow.mInputChannelToken, false);
        verify(mWm.mInputManager, never()).transferTouch(any(), anyInt());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testStatusBarTouchTransfer_doesNotFallBackToAnotherWindow() {
        final InsetsControlTarget target = mock(InsetsControlTarget.class);
        doReturn(mAppWindow).when(target).getWindow();
        mAppWindow.mInputChannelToken = new Binder();
        final DisplayPolicy policy = prepareStatusBarTouchTransfer(target);
        // The control window no longer owns the gesture; the shade may already have it.
        doReturn(false).when(mWm.mInputManager).transferTouchGesture(
                mAppWindow.mInputChannelToken, mStatusBarWindow.mInputChannelToken, false);

        policy.requestTransientBars(mStatusBarWindow, true);

        verify(mWm.mInputManager).transferTouchGesture(mAppWindow.mInputChannelToken,
                mStatusBarWindow.mInputChannelToken, false);
        verify(mWm.mInputManager, never()).transferTouch(any(), anyInt());
    }

    @SetupWindows(addWindows = { W_STATUS_BAR })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testStatusBarTouchTransfer_noControlWindow() {
        final InsetsControlTarget target = mock(InsetsControlTarget.class);
        final DisplayPolicy policy = prepareStatusBarTouchTransfer(target);

        policy.requestTransientBars(mStatusBarWindow, true);

        verify(target).showInsets(statusBars() | navigationBars(), null);
        verify(mWm.mInputManager, never()).transferTouchGesture(any(), any(), anyBoolean());
        verify(mWm.mInputManager, never()).transferTouch(any(), anyInt());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testStatusBarTouchTransfer_noSourceInputChannel() {
        final InsetsControlTarget target = mock(InsetsControlTarget.class);
        doReturn(mAppWindow).when(target).getWindow();
        mAppWindow.mInputChannelToken = null;
        final DisplayPolicy policy = prepareStatusBarTouchTransfer(target);

        policy.requestTransientBars(mStatusBarWindow, true);

        verify(target).showInsets(statusBars() | navigationBars(), null);
        verify(mWm.mInputManager, never()).transferTouchGesture(any(), any(), anyBoolean());
        verify(mWm.mInputManager, never()).transferTouch(any(), anyInt());
    }

    @SetupWindows(addWindows = { W_STATUS_BAR, W_NOTIFICATION_SHADE })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testStatusBarTouchTransfer_shadeControlsInsets() {
        final DisplayPolicy policy = prepareStatusBarTouchTransfer(mNotificationShadeWindow);
        policy.addWindowLw(mNotificationShadeWindow, mNotificationShadeWindow.mAttrs);

        policy.requestTransientBars(mStatusBarWindow, true);

        verify(mWm.mInputManager, never()).transferTouchGesture(any(), any(), anyBoolean());
        verify(mWm.mInputManager, never()).transferTouch(any(), anyInt());
    }

    private DisplayPolicy prepareStatusBarTouchTransfer(InsetsControlTarget target) {
        ((TestWindowManagerPolicy) mWm.mPolicy).mIsUserSetupComplete = true;
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        policy.addWindowLw(mStatusBarWindow, mStatusBarWindow.mAttrs);
        final InsetsSourceProvider provider = mStatusBarWindow.getControllableInsetProvider();
        spyOn(provider);
        doReturn(target).when(provider).getControlTarget();
        mStatusBarWindow.mInputChannelToken = new Binder();
        return policy;
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_NAVIGATION_BAR })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testCanSystemBarsBeShownByUser() {
        ((TestWindowManagerPolicy) mWm.mPolicy).mIsUserSetupComplete = true;
        mAppWindow.mAttrs.insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        mAppWindow.setRequestedVisibleTypes(0, navigationBars());
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        displayPolicy.addWindowLw(mNavBarWindow, mNavBarWindow.mAttrs);
        final InsetsSourceProvider navBarProvider = mNavBarWindow.getControllableInsetProvider();
        navBarProvider.updateControlForTarget(mAppWindow, false, null /* statsToken */);
        navBarProvider.getSource().setVisible(false);

        displayPolicy.setCanSystemBarsBeShownByUser(false);
        displayPolicy.requestTransientBars(mNavBarWindow, true);
        assertFalse(mDisplayContent.getInsetsPolicy().isTransient(navigationBars()));

        displayPolicy.setCanSystemBarsBeShownByUser(true);
        displayPolicy.requestTransientBars(mNavBarWindow, true);
        assertTrue(mDisplayContent.getInsetsPolicy().isTransient(navigationBars()));
    }

    @UseTestDisplay(addWindows = { W_NAVIGATION_BAR })
    @DisableFlags(Flags.FLAG_ENABLE_TRANSIENT_GESTURE_IN_SYSTEM_UI)
    @Test
    public void testTransientBarsSuppressedOnDreams() {
        final WindowState win = createDreamWindow();

        ((TestWindowManagerPolicy) mWm.mPolicy).mIsUserSetupComplete = true;
        win.mAttrs.insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        win.setRequestedVisibleTypes(0, navigationBars());

        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        displayPolicy.addWindowLw(mNavBarWindow, mNavBarWindow.mAttrs);
        final InsetsSourceProvider navBarProvider = mNavBarWindow.getControllableInsetProvider();
        navBarProvider.updateControlForTarget(win, false, null /* statsToken */);
        navBarProvider.getSource().setVisible(false);

        displayPolicy.setCanSystemBarsBeShownByUser(true);
        displayPolicy.requestTransientBars(mNavBarWindow, true);

        assertFalse(mDisplayContent.getInsetsPolicy().isTransient(navigationBars()));
    }

    @SetupWindows(addWindows = { W_ACTIVITY })
    @Test
    public void testSetSystemBarVisibilityOverride() {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final InsetsPolicy insetsPolicy = mDisplayContent.getInsetsPolicy();
        final Binder caller1 = new Binder();
        final Binder caller2 = new Binder();

        displayPolicy.applyPostLayoutPolicyLw(mAppWindow, mAppWindow.mAttrs, null, null);

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller1, statusBars(), 0);

        assertTrue(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller2, navigationBars(), 0);

        assertTrue(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertTrue(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller1, 0, 0);

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertTrue(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller2, 0, 0);

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller1, 0, statusBars());

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertTrue(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller2, 0, navigationBars());

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertTrue(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertTrue(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller1, 0, 0);

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertTrue(insetsPolicy.areTypesForciblyHidden(navigationBars()));

        displayPolicy.setSystemBarVisibilityOverride(caller2, 0, 0);

        assertFalse(insetsPolicy.areTypesForciblyShown(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyShown(navigationBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(statusBars()));
        assertFalse(insetsPolicy.areTypesForciblyHidden(navigationBars()));

    }

    @SetupWindows(addWindows = W_NAVIGATION_BAR)
    @Test
    public void testDumpDebug() throws InvalidProtocolBufferException {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final DisplayPolicy.DecorInsets decorInsets = displayPolicy.mDecorInsets;
        final DisplayPolicy.DecorInsets.Info info = decorInsets.get(0, 0, 0);
        info.mOverrideConfigInsets.set(1, 2, 3, 4);
        info.mOverrideNonDecorInsets.set(5, 6, 7, 8);
        info.mOverrideConfigFrame.set(9, 10, 11, 12);
        info.mOverrideNonDecorFrame.set(13, 14, 15, 16);
        final Binder caller = new Binder();
        displayPolicy.setSystemBarVisibilityOverride(caller, statusBars(), navigationBars());

        final String windowTitle = "LeftGestureHostWindow";
        final WindowState win = newWindowBuilder(windowTitle, TYPE_APPLICATION).build();
        final InsetsFrameProvider frameProvider = new InsetsFrameProvider(null, 0,
                systemGestures());
        frameProvider.setInsetsSize(Insets.of(50, 0, 0, 0));
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.providedInsets = new InsetsFrameProvider[] { frameProvider };
        displayPolicy.addWindowLw(win, attrs);
        final InsetsSourceProvider sourceProvider = win.getInsetsSourceProviders()
                .get(frameProvider.getId());
        sourceProvider.setServerVisible(true);
        sourceProvider.updateSourceFrame(win.getBounds());
        displayPolicy.applyPostLayoutPolicyLw(win, attrs, null, null);

        final ProtoOutputStream proto = new ProtoOutputStream();
        displayPolicy.dumpDebug(proto, DISPLAY_POLICY);

        final Windowmanagerservice.DisplayPolicyProto displayPolicyProto =
                Windowmanagerservice.DisplayContentProto.parseFrom(proto.getBytes())
                        .getDisplayPolicy();

        final Windowmanagerservice.DecorInsetsProto decorInsetsProto = displayPolicyProto
                .getDecorInsets();

        final Windowmanagerservice.DecorInsetsInfoProto infoProto = decorInsetsProto
                .getDecorInsetsInfoList().getFirst();
        assertEquals(info.mOverrideConfigInsets, extract(infoProto.getOverrideConfigInsets()));
        assertEquals(info.mOverrideNonDecorInsets, extract(infoProto.getOverrideNonDecorInsets()));
        assertEquals(info.mOverrideConfigFrame, extract(infoProto.getOverrideConfigFrame()));
        assertEquals(info.mOverrideNonDecorFrame, extract(infoProto.getOverrideNonDecorFrame()));

        assertEquals(1, displayPolicyProto.getSystemBarVisibilityOverrideCount());
        final Windowmanagerservice.SystemBarVisibilityOverrideProto sbvoProto =
                displayPolicyProto.getSystemBarVisibilityOverrideList().getFirst();
        assertEquals(caller.hashCode(), sbvoProto.getCaller());
        assertEquals(statusBars(), sbvoProto.getShow());
        assertEquals(navigationBars(), sbvoProto.getHide());

        final Windowmanagerservice.IdentifierProto leftGestureHostIdentifier =
                displayPolicyProto.getLeftGestureHostIdentifier();
        assertEquals(windowTitle, leftGestureHostIdentifier.getTitle());
    }

    @Test
    public void testShouldBeHiddenByKeyguard_ImeLayeringTarget_CannotShowWhenLocked() {
        ((TestWindowManagerPolicy) mWm.mPolicy).mKeyguardShowingAndNotOccluded = true;

        final WindowState imeWindow = createInputMethodWindow(true, true, false);
        mDisplayContent.setImeWindow(imeWindow);

        final WindowState appWindow = spy(createApplicationWindow());
        appWindow.mAttrs.flags &= ~FLAG_SHOW_WHEN_LOCKED;

        // Mock isDisplayed to return true
        doReturn(true).when(appWindow).isDisplayed();

        mDisplayContent.setImeLayeringTarget(appWindow);

        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();

        assertTrue(displayPolicy.shouldBeHiddenByKeyguard(imeWindow, appWindow));
    }

    @Test
    public void testShouldBeHiddenByKeyguard_ImeLayeringTarget_CanShowWhenLocked() {
        ((TestWindowManagerPolicy) mWm.mPolicy).mKeyguardShowingAndNotOccluded = true;

        final WindowState imeWindow = createInputMethodWindow(true, true, false);
        mDisplayContent.setImeWindow(imeWindow);

        final WindowState appWindow = spy(createApplicationWindow());
        appWindow.mAttrs.flags |= FLAG_SHOW_WHEN_LOCKED;

        // Mock isDisplayed to return true
        doReturn(true).when(appWindow).isDisplayed();

        mDisplayContent.setImeLayeringTarget(appWindow);

        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();

        assertFalse(displayPolicy.shouldBeHiddenByKeyguard(imeWindow, appWindow));
    }
}
