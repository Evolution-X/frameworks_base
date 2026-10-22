/*
 * Copyright (C) 2026 Evolution X
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

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression tests ensuring status-icon width helpers do not double-count padding. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusIconContainerSpacingTest extends SysuiTestCase {

    @Test
    public void measuredWidthDoesNotCountChildPaddingTwice() {
        View child = new View(mContext);
        child.setPadding(5, 0, 7, 0);
        child.measure(
                View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(20, View.MeasureSpec.EXACTLY));

        assertThat(child.getMeasuredWidth()).isEqualTo(32);
        assertThat(StatusIconContainer.getViewTotalMeasuredWidth(child)).isEqualTo(32);
    }

    @Test
    public void laidOutWidthDoesNotCountChildPaddingTwice() {
        View child = new View(mContext);
        child.setPadding(5, 0, 7, 0);
        child.layout(0, 0, 32, 20);

        assertThat(child.getWidth()).isEqualTo(32);
        assertThat(StatusIconContainer.getViewTotalWidth(child)).isEqualTo(32);
    }
}
