/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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
package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.animation.*
import android.widget.FrameLayout
import androidx.core.view.isVisible
import kotlin.math.*
import kotlin.random.Random

class ChargingAnimationView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "ChargingAnimationView"
        const val STYLE_BUBBLE_STREAM = 0
        const val STYLE_NEON = 1
        const val STYLE_BEAM = 2
        const val STYLE_PLASMA = 3
        const val STYLE_QUANTUM_SPARKS = 4 
        const val STYLE_NEBULA = 5
        const val STYLE_DIGITAL_MATRIX = 6
        const val STYLE_GEOFLOW = 7
        
        const val COLOR_MODE_DEFAULT = 0
        const val COLOR_MODE_ACCENT = 1
        const val COLOR_MODE_RAINBOW = 2
        
        private const val BUBBLE_DURATION = 2000L
        private const val NEON_PULSE_DURATION = 1500L
        private const val BEAM_DURATION = 1800L
        private const val PLASMA_DURATION = 1800L
        private const val QUANTUM_DURATION = 1600L
        private const val NEBULA_DURATION = 2200L
        private const val MATRIX_DURATION = 2000L
        private const val GEOFLOW_DURATION = 2500L
        
        private const val MAX_BUBBLES = 30
        private const val MAX_PARTICLES = 40
        private const val MAX_BEAM_PARTICLES = 25
        private const val MAX_QUANTUM_SPARKS = 50
        private const val MAX_NEBULA_CLOUDS = 20
        private const val MAX_MATRIX_CHARS = 30
        private const val MAX_GEO_SHAPES = 25
        private const val MAX_TRAIL_SIZE = 10
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val neonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val plasmaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val matrixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val geoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val geoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val shapePath = Path()

    var batteryLevel: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var animationStyle: Int = STYLE_BUBBLE_STREAM
        set(value) {
            field = value.coerceIn(STYLE_BUBBLE_STREAM, STYLE_GEOFLOW)
            invalidate()
        }

    var colorMode: Int = COLOR_MODE_DEFAULT
        set(value) {
            field = value.coerceIn(COLOR_MODE_DEFAULT, COLOR_MODE_RAINBOW)
            invalidate()
        }

    var rippleOpacity: Float = 0.6f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var glowIntensity: Float = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var arcCount: Int = 3
        set(value) {
            field = value.coerceIn(1, 6)
            resetBeams()
            resetPlasmaBlobs()
            invalidate()
        }

    var defaultChargingColor: Int = 0xFF4CAF50.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = 0xFF2196F3.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private var bubbleAnimator: ValueAnimator? = null
    private var neonAnimator: ValueAnimator? = null
    private var beamAnimator: ValueAnimator? = null
    private var plasmaAnimator: ValueAnimator? = null
    private var quantumAnimator: ValueAnimator? = null
    private var nebulaAnimator: ValueAnimator? = null
    private var matrixAnimator: ValueAnimator? = null
    private var geoflowAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null

    private var currentAlpha: Float = 0f
    private var bubbleProgress: Float = 0f
    private var neonGlowRadius: Float = 0f
    private var beamProgress: Float = 0f
    private var plasmaProgress: Float = 0f
    private var quantumProgress: Float = 0f
    private var nebulaProgress: Float = 0f
    private var matrixProgress: Float = 0f
    private var geoflowProgress: Float = 0f
    private var rainbowHue: Float = 0f

    private data class Bubble(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityY: Float = 0f,
        var velocityX: Float = 0f,
        var life: Float = 0f,
        var size: Float = 0f,
        var wobblePhase: Float = 0f,
        var wobbleSpeed: Float = 0f,
        var active: Boolean = false
    )

    private data class Particle(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityY: Float = 0f,
        var velocityX: Float = 0f,
        var life: Float = 0f,
        var size: Float = 0f,
        var active: Boolean = false
    )

    private data class BeamParticle(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityY: Float = 0f,
        var size: Float = 0f,
        var life: Float = 0f,
        var active: Boolean = false,
        val trail: MutableList<Pair<Float, Float>> = mutableListOf()
    )

    private data class PlasmaBlob(
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 0f,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var phase: Float = 0f,
        var hueOffset: Float = 0f,
        var active: Boolean = false
    )

    private data class QuantumSpark(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var life: Float = 0f,
        var size: Float = 0f,
        var brightness: Float = 0f,
        var active: Boolean = false,
        val sparkTrail: MutableList<Pair<Float, Float>> = mutableListOf()
    )

    private data class NebulaCloud(
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 0f,
        var velocityY: Float = 0f,
        var life: Float = 0f,
        var expansion: Float = 0f,
        var rotation: Float = 0f,
        var opacity: Float = 0f,
        var active: Boolean = false
    )

    private data class MatrixChar(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityY: Float = 0f,
        var char: String = "",
        var life: Float = 0f,
        var brightness: Float = 0f,
        var active: Boolean = false,
        val trail: MutableList<Triple<Float, Float, Float>> = mutableListOf()
    )

    private data class GeoShape(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityY: Float = 0f,
        var size: Float = 0f,
        var rotation: Float = 0f,
        var rotationSpeed: Float = 0f,
        var life: Float = 0f,
        var shapeType: Int = 0,
        var morphProgress: Float = 0f,
        var targetShape: Int = 0,
        var active: Boolean = false
    )

    private val bubblePool = Array(MAX_BUBBLES) { Bubble() }
    private val particlePool = Array(MAX_PARTICLES) { Particle() }
    private val beamParticlePool = Array(MAX_BEAM_PARTICLES) { BeamParticle() }
    private val quantumSparkPool = Array(MAX_QUANTUM_SPARKS) { QuantumSpark() }
    private val nebulaCloudPool = Array(MAX_NEBULA_CLOUDS) { NebulaCloud() }
    private val matrixCharPool = Array(MAX_MATRIX_CHARS) { MatrixChar() }
    private val geoShapePool = Array(MAX_GEO_SHAPES) { GeoShape() }
    private val bubbles = mutableListOf<Bubble>()
    private val particles = mutableListOf<Particle>()
    private val beamParticles = mutableListOf<BeamParticle>()
    private val plasmaBlobs = mutableListOf<PlasmaBlob>()
    private val quantumSparks = mutableListOf<QuantumSpark>()
    private val nebulaClouds = mutableListOf<NebulaCloud>()
    private val matrixChars = mutableListOf<MatrixChar>()
    private val geoShapes = mutableListOf<GeoShape>()
    
    private val usbPortY: Float by lazy { height.toFloat() }
    private val usbPortX: Float by lazy { width / 2f }

    private val matrixCharSet = "01アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visible = false
        resetBeams()
        resetPlasmaBlobs()
    }

    private fun getCurrentColor(): Int {
        return when (colorMode) {
            COLOR_MODE_ACCENT -> accentColor
            COLOR_MODE_RAINBOW -> hsvToColor(rainbowHue)
            else -> defaultChargingColor
        }
    }

    private fun hsvToColor(hue: Float): Int {
        val hsv = floatArrayOf(hue, 0.8f, 0.9f)
        return Color.HSVToColor(hsv)
    }

    private fun acquireBubble(): Bubble? {
        return bubblePool.firstOrNull { !it.active }?.apply { active = true }
    }

    private fun releaseBubble(bubble: Bubble) {
        bubble.active = false
    }

    private fun acquireParticle(): Particle? {
        return particlePool.firstOrNull { !it.active }?.apply { active = true }
    }

    private fun releaseParticle(particle: Particle) {
        particle.active = false
    }

    private fun acquireBeamParticle(): BeamParticle? {
        return beamParticlePool.firstOrNull { !it.active }?.apply {
            active = true
            trail.clear()
        }
    }

    private fun releaseBeamParticle(particle: BeamParticle) {
        particle.active = false
        particle.trail.clear()
    }

    private fun acquireQuantumSpark(): QuantumSpark? {
        return quantumSparkPool.firstOrNull { !it.active }?.apply {
            active = true
            sparkTrail.clear()
        }
    }

    private fun releaseQuantumSpark(spark: QuantumSpark) {
        spark.active = false
        spark.sparkTrail.clear()
    }

    private fun acquireNebulaCloud(): NebulaCloud? {
        return nebulaCloudPool.firstOrNull { !it.active }?.apply { active = true }
    }

    private fun releaseNebulaCloud(cloud: NebulaCloud) {
        cloud.active = false
    }

    private fun acquireMatrixChar(): MatrixChar? {
        return matrixCharPool.firstOrNull { !it.active }?.apply {
            active = true
            trail.clear()
        }
    }

    private fun releaseMatrixChar(char: MatrixChar) {
        char.active = false
        char.trail.clear()
    }

    private fun acquireGeoShape(): GeoShape? {
        return geoShapePool.firstOrNull { !it.active }?.apply { active = true }
    }

    private fun releaseGeoShape(shape: GeoShape) {
        shape.active = false
    }

    private fun resetBeams() {
        beamParticles.forEach { releaseBeamParticle(it) }
        beamParticles.clear()
    }

    private fun spawnBeamParticle() {
        val particle = acquireBeamParticle() ?: return
        particle.x = usbPortX + (Random.nextFloat() - 0.5f) * 80f
        particle.y = usbPortY
        particle.velocityY = -4f - Random.nextFloat() * 6f
        particle.size = 6f + Random.nextFloat() * 10f
        particle.life = 1f
        beamParticles.add(particle)
    }

    private fun resetParticles() {
        particles.forEach { releaseParticle(it) }
        particles.clear()
        for (i in 0 until 30) {
            spawnParticle()
        }
    }

    private fun spawnParticle() {
        val particle = acquireParticle() ?: return
        particle.x = usbPortX + (Random.nextFloat() - 0.5f) * 40f
        particle.y = usbPortY
        particle.velocityY = -3f - Random.nextFloat() * 5f
        particle.velocityX = (Random.nextFloat() - 0.5f) * 2f
        particle.life = 1f
        particle.size = 4f + Random.nextFloat() * 8f
        particles.add(particle)
    }

    private fun resetPlasmaBlobs() {
        plasmaBlobs.clear()
        val centerX = width / 2f
        val centerY = height / 2f
        
        for (i in 0 until 5) {
            val angle = (i.toFloat() / 5f) * PI.toFloat() * 2f
            val distance = 100f + Random.nextFloat() * 50f
            
            plasmaBlobs.add(
                PlasmaBlob(
                    x = centerX + cos(angle) * distance,
                    y = centerY + sin(angle) * distance,
                    radius = 60f + Random.nextFloat() * 40f,
                    velocityX = (Random.nextFloat() - 0.5f) * 2f,
                    velocityY = (Random.nextFloat() - 0.5f) * 2f,
                    phase = Random.nextFloat() * PI.toFloat() * 2f,
                    hueOffset = (i.toFloat() / 5f) * 360f,
                    active = true
                )
            )
        }
    }

    private fun resetBubbles() {
        bubbles.forEach { releaseBubble(it) }
        bubbles.clear()
    }

    private fun spawnBubble() {
        val bubble = acquireBubble() ?: return
        bubble.x = usbPortX + (Random.nextFloat() - 0.5f) * 60f
        bubble.y = usbPortY
        bubble.velocityY = -2.5f - Random.nextFloat() * 3.5f
        bubble.velocityX = (Random.nextFloat() - 0.5f) * 1.5f
        bubble.life = 1f
        bubble.size = 15f + Random.nextFloat() * 25f
        bubble.wobblePhase = Random.nextFloat() * PI.toFloat() * 2f
        bubble.wobbleSpeed = 0.05f + Random.nextFloat() * 0.1f
        bubbles.add(bubble)
    }

    private fun resetQuantumSparks() {
        quantumSparks.forEach { releaseQuantumSpark(it) }
        quantumSparks.clear()
    }

    private fun spawnQuantumSpark() {
        val burstCount = 3 + Random.nextInt(5)
        for (i in 0 until burstCount) {
            val spark = acquireQuantumSpark() ?: continue
            val angle = Random.nextFloat() * PI.toFloat() * 2f
            val speed = 3f + Random.nextFloat() * 5f
            
            spark.x = usbPortX + (Random.nextFloat() - 0.5f) * 40f
            spark.y = usbPortY - 20f
            spark.velocityX = cos(angle) * speed
            spark.velocityY = sin(angle) * speed - 2f
            spark.life = 1f
            spark.size = 3f + Random.nextFloat() * 5f
            spark.brightness = 0.8f + Random.nextFloat() * 0.2f
            quantumSparks.add(spark)
        }
    }

    private fun resetNebulaClouds() {
        nebulaClouds.forEach { releaseNebulaCloud(it) }
        nebulaClouds.clear()
    }

    private fun spawnNebulaCloud() {
        val cloud = acquireNebulaCloud() ?: return
        cloud.x = usbPortX + (Random.nextFloat() - 0.5f) * 100f
        cloud.y = usbPortY
        cloud.radius = 30f + Random.nextFloat() * 40f
        cloud.velocityY = -1f - Random.nextFloat() * 2f
        cloud.life = 1f
        cloud.expansion = 1f
        cloud.rotation = Random.nextFloat() * 360f
        cloud.opacity = 0.6f + Random.nextFloat() * 0.3f
        nebulaClouds.add(cloud)
    }

    private fun resetMatrixChars() {
        matrixChars.forEach { releaseMatrixChar(it) }
        matrixChars.clear()
    }

    private fun spawnMatrixChar() {
        val matrixChar = acquireMatrixChar() ?: return
        matrixChar.char = matrixCharSet[Random.nextInt(matrixCharSet.length)].toString()
        matrixChar.x = usbPortX + (Random.nextFloat() - 0.5f) * 200f
        matrixChar.y = usbPortY
        matrixChar.velocityY = -3f - Random.nextFloat() * 4f
        matrixChar.life = 1f
        matrixChar.brightness = 0.7f + Random.nextFloat() * 0.3f
        matrixChars.add(matrixChar)
    }

    private fun resetGeoShapes() {
        geoShapes.forEach { releaseGeoShape(it) }
        geoShapes.clear()
    }

    private fun spawnGeoShape() {
        val shape = acquireGeoShape() ?: return
        shape.shapeType = Random.nextInt(4)
        shape.x = usbPortX + (Random.nextFloat() - 0.5f) * 80f
        shape.y = usbPortY
        shape.velocityY = -2f - Random.nextFloat() * 3f
        shape.size = 20f + Random.nextFloat() * 30f
        shape.rotation = Random.nextFloat() * 360f
        shape.rotationSpeed = (Random.nextFloat() - 0.5f) * 5f
        shape.life = 1f
        shape.targetShape = (shape.shapeType + 1 + Random.nextInt(3)) % 4
        shape.morphProgress = 0f
        geoShapes.add(shape)
    }

    fun show() {
        post {
            visible = true
            fadeAnimator?.cancel()
            
            fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 1f).apply {
                duration = 300
                addUpdateListener { animator ->
                    currentAlpha = animator.animatedValue as Float
                    alpha = currentAlpha
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        fadeAnimator = null
                        startAnimations()
                    }
                })
                start()
            }
        }
    }

    fun hide() {
        post {
            fadeAnimator?.cancel()
            stopAllAnimations()
            
            fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 0f).apply {
                duration = 300
                addUpdateListener { animator ->
                    currentAlpha = animator.animatedValue as Float
                    alpha = currentAlpha
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        post {
                            visible = false
                            fadeAnimator = null
                        }
                    }
                })
                start()
            }
        }
    }

    private fun startAnimations() {
        if (!visible) return

        if (colorMode == COLOR_MODE_RAINBOW) {
            startRainbowAnimation()
        }

        when (animationStyle) {
            STYLE_BUBBLE_STREAM -> startBubbleStreamAnimation()
            STYLE_NEON -> startNeonAnimation()
            STYLE_BEAM -> startBeamAnimation()
            STYLE_PLASMA -> startPlasmaAnimation()
            STYLE_QUANTUM_SPARKS -> startQuantumSparksAnimation()
            STYLE_NEBULA -> startNebulaAnimation()
            STYLE_DIGITAL_MATRIX -> startDigitalMatrixAnimation()
            STYLE_GEOFLOW -> startGeoFlowAnimation()
        }
    }

    private fun startRainbowAnimation() {
        rainbowAnimator?.cancel()
        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 5000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                rainbowHue = animator.animatedValue as Float
                invalidate()
            }
            
            start()
        }
    }

    private fun startBubbleStreamAnimation() {
        bubbleAnimator?.cancel()
        resetBubbles()
        
        bubbleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BUBBLE_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                bubbleProgress = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.2f) {
                    spawnBubble()
                }
                
                val iterator = bubbles.iterator()
                while (iterator.hasNext()) {
                    val bubble = iterator.next()
                    bubble.wobblePhase += bubble.wobbleSpeed
                    val wobble = sin(bubble.wobblePhase) * 3f
                    
                    bubble.x += bubble.velocityX + wobble
                    bubble.y += bubble.velocityY
                    bubble.life -= 0.008f
                    
                    bubble.size = bubble.size * (1f + sin(bubble.wobblePhase * 2f) * 0.05f)
                    
                    if (bubble.life <= 0f || bubble.y < -50f) {
                        releaseBubble(bubble)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startNeonAnimation() {
        neonAnimator?.cancel()
        resetParticles()
        
        neonAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NEON_PULSE_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            
            addUpdateListener { animator ->
                neonGlowRadius = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.3f) {
                    spawnParticle()
                }
                
                val iterator = particles.iterator()
                while (iterator.hasNext()) {
                    val particle = iterator.next()
                    particle.y += particle.velocityY
                    particle.x += particle.velocityX
                    particle.life -= 0.02f
                    
                    if (particle.life <= 0f || particle.y < 0f) {
                        releaseParticle(particle)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startBeamAnimation() {
        beamAnimator?.cancel()
        resetBeams()
        
        beamAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BEAM_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                beamProgress = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.15f) {
                    spawnBeamParticle()
                }
                
                val iterator = beamParticles.iterator()
                while (iterator.hasNext()) {
                    val particle = iterator.next()
                    particle.trail.add(0, Pair(particle.x, particle.y))
                    if (particle.trail.size > 8) {
                        particle.trail.removeAt(particle.trail.size - 1)
                    }
                    
                    particle.y += particle.velocityY
                    particle.life -= 0.015f
                    
                    if (particle.life <= 0f || particle.y < 0f) {
                        releaseBeamParticle(particle)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startPlasmaAnimation() {
        plasmaAnimator?.cancel()
        resetPlasmaBlobs()
        
        plasmaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PLASMA_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                plasmaProgress = animator.animatedValue as Float
                
                plasmaBlobs.forEach { blob ->
                    blob.x += blob.velocityX
                    blob.y += blob.velocityY
                    blob.phase += 0.05f
                    
                    if (blob.x < blob.radius || blob.x > width - blob.radius) {
                        blob.velocityX *= -1f
                    }
                    if (blob.y < blob.radius || blob.y > height - blob.radius) {
                        blob.velocityY *= -1f
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startQuantumSparksAnimation() {
        quantumAnimator?.cancel()
        resetQuantumSparks()
        
        quantumAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = QUANTUM_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                quantumProgress = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.08f) {
                    spawnQuantumSpark()
                }
                
                val iterator = quantumSparks.iterator()
                while (iterator.hasNext()) {
                    val spark = iterator.next()
                    spark.sparkTrail.add(0, Pair(spark.x, spark.y))
                    if (spark.sparkTrail.size > 5) {
                        spark.sparkTrail.removeAt(spark.sparkTrail.size - 1)
                    }
                    
                    spark.velocityY += 0.15f
                    spark.x += spark.velocityX
                    spark.y += spark.velocityY
                    spark.life -= 0.02f
                    
                    if (spark.x < 0 || spark.x > width) {
                        spark.velocityX *= -0.7f
                    }
                    
                    spark.brightness = (0.6f + Random.nextFloat() * 0.4f).coerceIn(0f, 1f)
                    
                    if (spark.life <= 0f || spark.y > height + 50f) {
                        releaseQuantumSpark(spark)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startNebulaAnimation() {
        nebulaAnimator?.cancel()
        resetNebulaClouds()
        
        nebulaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NEBULA_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                nebulaProgress = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.06f) {
                    spawnNebulaCloud()
                }
                
                val iterator = nebulaClouds.iterator()
                while (iterator.hasNext()) {
                    val cloud = iterator.next()
                    cloud.y += cloud.velocityY
                    cloud.expansion += 0.01f
                    cloud.rotation += 0.5f
                    cloud.life -= 0.005f
                    cloud.opacity = (cloud.life * 0.7f).coerceIn(0f, 0.9f)
                    cloud.x += sin(cloud.rotation * 0.1f) * 0.5f
                    
                    if (cloud.life <= 0f || cloud.y < -100f) {
                        releaseNebulaCloud(cloud)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startDigitalMatrixAnimation() {
        matrixAnimator?.cancel()
        resetMatrixChars()
        
        matrixAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MATRIX_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                matrixProgress = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.15f) {
                    spawnMatrixChar()
                }
                
                val iterator = matrixChars.iterator()
                while (iterator.hasNext()) {
                    val char = iterator.next()
                    char.trail.add(0, Triple(char.x, char.y, char.brightness))
                    if (char.trail.size > 10) {
                        char.trail.removeAt(char.trail.size - 1)
                    }
                    
                    char.y += char.velocityY
                    char.life -= 0.01f
                    char.brightness = (char.brightness + (Random.nextFloat() - 0.5f) * 0.1f).coerceIn(0.3f, 1f)
                    
                    if (char.life <= 0f || char.y < -50f) {
                        releaseMatrixChar(char)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun startGeoFlowAnimation() {
        geoflowAnimator?.cancel()
        resetGeoShapes()
        
        geoflowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GEOFLOW_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                geoflowProgress = animator.animatedValue as Float
                
                if (Random.nextFloat() < 0.1f) {
                    spawnGeoShape()
                }
                
                val iterator = geoShapes.iterator()
                while (iterator.hasNext()) {
                    val shape = iterator.next()
                    shape.y += shape.velocityY
                    shape.rotation += shape.rotationSpeed
                    shape.life -= 0.008f
                    
                    shape.morphProgress += 0.01f
                    if (shape.morphProgress >= 1f) {
                        shape.morphProgress = 0f
                        shape.shapeType = shape.targetShape
                        shape.targetShape = (shape.shapeType + 1 + Random.nextInt(3)) % 4
                    }
                    
                    if (shape.life <= 0f || shape.y < -100f) {
                        releaseGeoShape(shape)
                        iterator.remove()
                    }
                }
                
                invalidate()
            }
            
            start()
        }
    }

    private fun stopAllAnimations() {
        bubbleAnimator?.cancel()
        bubbleAnimator = null
        neonAnimator?.cancel()
        neonAnimator = null
        beamAnimator?.cancel()
        beamAnimator = null
        plasmaAnimator?.cancel()
        plasmaAnimator = null
        quantumAnimator?.cancel()
        quantumAnimator = null
        nebulaAnimator?.cancel()
        nebulaAnimator = null
        matrixAnimator?.cancel()
        matrixAnimator = null
        geoflowAnimator?.cancel()
        geoflowAnimator = null
        rainbowAnimator?.cancel()
        rainbowAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeAnimator?.cancel()
        fadeAnimator = null
        stopAllAnimations()
        bubbles.forEach { releaseBubble(it) }
        bubbles.clear()
        particles.forEach { releaseParticle(it) }
        particles.clear()
        beamParticles.forEach { releaseBeamParticle(it) }
        beamParticles.clear()
        quantumSparks.forEach { releaseQuantumSpark(it) }
        quantumSparks.clear()
        nebulaClouds.forEach { releaseNebulaCloud(it) }
        nebulaClouds.clear()
        matrixChars.forEach { releaseMatrixChar(it) }
        matrixChars.clear()
        geoShapes.forEach { releaseGeoShape(it) }
        geoShapes.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val usbY = height.toFloat()
        val maxHeight = height * 0.35f
        val currentColor = getCurrentColor()

        when (animationStyle) {
            STYLE_BUBBLE_STREAM -> drawBubbleStream(canvas, currentColor, usbY, maxHeight)
            STYLE_NEON -> drawNeon(canvas, currentColor, centerX, usbY, maxHeight)
            STYLE_BEAM -> drawBeam(canvas, currentColor, usbY, maxHeight)
            STYLE_PLASMA -> drawPlasma(canvas, currentColor, usbY)
            STYLE_QUANTUM_SPARKS -> drawQuantumSparks(canvas, currentColor)
            STYLE_NEBULA -> drawNebula(canvas, currentColor, usbY, maxHeight)
            STYLE_DIGITAL_MATRIX -> drawDigitalMatrix(canvas, currentColor, usbY, maxHeight)
            STYLE_GEOFLOW -> drawGeoFlow(canvas, currentColor, usbY, maxHeight)
        }
    }

    private fun drawBubbleStream(canvas: Canvas, currentColor: Int, usbY: Float, maxHeight: Float) {
        if (bubbleAnimator?.isRunning != true) return
        
        bubbles.forEach { bubble ->
            val heightProgress = (usbY - bubble.y) / maxHeight
            val heightFade = (1f - heightProgress.coerceIn(0f, 1f))
            
            val alpha = (255 * bubble.life * heightFade * rippleOpacity).toInt()
            val gradient = RadialGradient(
                bubble.x, bubble.y, bubble.size,
                intArrayOf(
                    currentColor and 0x00FFFFFF or ((alpha * 0.8f).toInt() shl 24),
                    currentColor and 0x00FFFFFF or ((alpha * 0.4f).toInt() shl 24),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.3f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            
            bubblePaint.shader = gradient
            canvas.drawCircle(bubble.x, bubble.y, bubble.size, bubblePaint)
            
            val highlightAlpha = (alpha * 0.6f).toInt()
            bubblePaint.shader = null
            bubblePaint.color = Color.WHITE and 0x00FFFFFF or (highlightAlpha shl 24)
            canvas.drawCircle(
                bubble.x - bubble.size * 0.3f,
                bubble.y - bubble.size * 0.3f,
                bubble.size * 0.3f,
                bubblePaint
            )
        }
    }

    private fun drawNeon(canvas: Canvas, currentColor: Int, centerX: Float, usbY: Float, maxHeight: Float) {
        if (neonAnimator?.isRunning != true) return
        
        val glowHeight = 150f + (neonGlowRadius * 100f * glowIntensity)
        val maxGlowHeight = maxHeight * 0.8f
        val actualGlowHeight = minOf(glowHeight, maxGlowHeight)
        
        val shader = LinearGradient(
            centerX, usbY, centerX, usbY - actualGlowHeight,
            intArrayOf(
                currentColor and 0x00FFFFFF or ((200 * glowIntensity).toInt() shl 24),
                currentColor and 0x00FFFFFF or ((100 * glowIntensity).toInt() shl 24),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        neonGlowPaint.shader = shader
        
        val rect = RectF(centerX - 50f, usbY - actualGlowHeight, centerX + 50f, usbY)
        canvas.drawRect(rect, neonGlowPaint)
        
        particles.forEach { particle ->
            val heightProgress = (usbY - particle.y) / maxHeight
            val heightFade = (1f - heightProgress.coerceIn(0f, 1f))
            val particleAlpha = (255 * particle.life * heightFade).toInt()
            neonGlowPaint.shader = null
            neonGlowPaint.color = currentColor and 0x00FFFFFF or (particleAlpha shl 24)
            canvas.drawCircle(particle.x, particle.y, particle.size, neonGlowPaint)
        }
    }

    private fun drawBeam(canvas: Canvas, currentColor: Int, usbY: Float, maxHeight: Float) {
        if (beamAnimator?.isRunning != true) return
        
        beamParticles.forEach { particle ->
            val heightProgress = (usbY - particle.y) / maxHeight
            val heightFade = (1f - heightProgress.coerceIn(0f, 1f))
            
            for (i in particle.trail.indices) {
                val trailFade = (1f - (i.toFloat() / particle.trail.size))
                val alpha = (255 * particle.life * heightFade * trailFade * 0.6f).toInt()
                val size = particle.size * trailFade
                
                beamPaint.color = currentColor and 0x00FFFFFF or (alpha shl 24)
                canvas.drawCircle(particle.trail[i].first, particle.trail[i].second, size, beamPaint)
            }
            
            val alpha = (255 * particle.life * heightFade).toInt()
            beamPaint.color = currentColor and 0x00FFFFFF or (alpha shl 24)
            canvas.drawCircle(particle.x, particle.y, particle.size, beamPaint)
        }
    }

    private fun drawPlasma(canvas: Canvas, currentColor: Int, usbY: Float) {
        if (plasmaAnimator?.isRunning != true) return
        
        plasmaBlobs.forEach { blob ->
            val pulsate = 1f + sin(blob.phase) * 0.2f
            val radius = blob.radius * pulsate
            
            val distanceFromBottom = usbY - blob.y
            val heightProgress = (distanceFromBottom / height).coerceIn(0f, 1f)
            val heightFade = 1f - (heightProgress * 0.5f)
            
            val baseAlpha = (255 * glowIntensity * heightFade).toInt()
            
            val blobColor = if (colorMode == COLOR_MODE_RAINBOW) {
                hsvToColor((rainbowHue + blob.hueOffset) % 360f)
            } else {
                currentColor
            }
            
            val gradient = RadialGradient(
                blob.x, blob.y, radius,
                intArrayOf(
                    blobColor and 0x00FFFFFF or (baseAlpha shl 24),
                    blobColor and 0x00FFFFFF or ((baseAlpha / 2) shl 24),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            
            plasmaPaint.shader = gradient
            canvas.drawCircle(blob.x, blob.y, radius, plasmaPaint)
        }
    }

    private fun drawQuantumSparks(canvas: Canvas, currentColor: Int) {
        if (quantumAnimator?.isRunning != true) return
        
        quantumSparks.forEach { spark ->
            for (i in spark.sparkTrail.indices) {
                val trailFade = (1f - (i.toFloat() / spark.sparkTrail.size))
                val alpha = (255 * spark.life * trailFade * 0.5f).toInt()
                
                sparkPaint.color = currentColor and 0x00FFFFFF or (alpha shl 24)
                canvas.drawCircle(
                    spark.sparkTrail[i].first,
                    spark.sparkTrail[i].second,
                    spark.size * trailFade * 0.6f,
                    sparkPaint
                )
            }
            
            val alpha = (255 * spark.life * spark.brightness).toInt()
            val glowRadius = spark.size * 2f
            
            val gradient = RadialGradient(
                spark.x, spark.y, glowRadius,
                intArrayOf(
                    Color.WHITE and 0x00FFFFFF or (alpha shl 24),
                    currentColor and 0x00FFFFFF or ((alpha * 0.7f).toInt() shl 24),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            
            sparkPaint.shader = gradient
            canvas.drawCircle(spark.x, spark.y, glowRadius, sparkPaint)
            
            sparkPaint.shader = null
            sparkPaint.color = Color.WHITE and 0x00FFFFFF or (alpha shl 24)
            canvas.drawCircle(spark.x, spark.y, spark.size, sparkPaint)
        }
    }

    private fun drawNebula(canvas: Canvas, currentColor: Int, usbY: Float, maxHeight: Float) {
        if (nebulaAnimator?.isRunning != true) return
        
        nebulaClouds.forEach { cloud ->
            val heightProgress = (usbY - cloud.y) / maxHeight
            val heightFade = (1f - heightProgress.coerceIn(0f, 1f))
            
            val expandedRadius = cloud.radius * cloud.expansion
            val alpha = (255 * cloud.opacity * heightFade * glowIntensity).toInt()
            
            for (layer in 0..2) {
                val layerScale = 1f + (layer * 0.3f)
                val layerAlpha = (alpha / (layer + 1f)).toInt()
                
                val gradient = RadialGradient(
                    cloud.x, cloud.y, expandedRadius * layerScale,
                    intArrayOf(
                        currentColor and 0x00FFFFFF or (layerAlpha shl 24),
                        currentColor and 0x00FFFFFF or ((layerAlpha * 0.5f).toInt() shl 24),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                
                nebulaPaint.shader = gradient
                
                canvas.save()
                canvas.rotate(cloud.rotation + (layer * 30f), cloud.x, cloud.y)
                canvas.drawCircle(cloud.x, cloud.y, expandedRadius * layerScale, nebulaPaint)
                canvas.restore()
            }
        }
    }

    private fun drawDigitalMatrix(canvas: Canvas, currentColor: Int, usbY: Float, maxHeight: Float) {
        if (matrixAnimator?.isRunning != true) return
        
        matrixChars.forEach { char ->
            val heightProgress = (usbY - char.y) / maxHeight
            val heightFade = (1f - heightProgress.coerceIn(0f, 1f))
            
            for (i in char.trail.indices) {
                val trailFade = (1f - (i.toFloat() / char.trail.size))
                val (tx, ty, brightness) = char.trail[i]
                val alpha = (255 * char.life * heightFade * trailFade * 0.4f).toInt()
                
                matrixPaint.color = currentColor and 0x00FFFFFF or (alpha shl 24)
                canvas.drawText(char.char, tx, ty, matrixPaint)
            }
            
            val alpha = (255 * char.life * heightFade * char.brightness).toInt()
            
            matrixPaint.setShadowLayer(10f, 0f, 0f, currentColor and 0x00FFFFFF or (alpha shl 24))
            matrixPaint.color = currentColor and 0x00FFFFFF or (alpha shl 24)
            canvas.drawText(char.char, char.x, char.y, matrixPaint)
            
            matrixPaint.clearShadowLayer()
            matrixPaint.color = Color.WHITE and 0x00FFFFFF or ((alpha * 0.8f).toInt() shl 24)
            canvas.drawText(char.char, char.x, char.y, matrixPaint)
        }
    }

    private fun drawGeoFlow(canvas: Canvas, currentColor: Int, usbY: Float, maxHeight: Float) {
        if (geoflowAnimator?.isRunning != true) return
        
        geoShapes.forEach { shape ->
            val heightProgress = (usbY - shape.y) / maxHeight
            val heightFade = (1f - heightProgress.coerceIn(0f, 1f))
            val alpha = (255 * shape.life * heightFade).toInt()
            
            canvas.save()
            canvas.translate(shape.x, shape.y)
            canvas.rotate(shape.rotation)
            
            val currentShape = if (shape.morphProgress < 0.5f) shape.shapeType else shape.targetShape
            val morphFactor = if (shape.morphProgress < 0.5f) {
                shape.morphProgress * 2f
            } else {
                (1f - shape.morphProgress) * 2f
            }
            
            val size = shape.size * (1f - morphFactor * 0.3f)
            
            geoFillPaint.color = currentColor and 0x00FFFFFF or ((alpha * 0.3f).toInt() shl 24)
            drawShape(canvas, currentShape, size, geoFillPaint)
            
            geoPaint.color = currentColor and 0x00FFFFFF or (alpha shl 24)
            geoPaint.setShadowLayer(8f, 0f, 0f, currentColor and 0x00FFFFFF or ((alpha * 0.6f).toInt() shl 24))
            drawShape(canvas, currentShape, size, geoPaint)
            geoPaint.clearShadowLayer()
            
            canvas.restore()
        }
    }

    private fun drawShape(canvas: Canvas, shapeType: Int, size: Float, paint: Paint) {
        shapePath.reset()
        
        when (shapeType) {
            0 -> {
                shapePath.moveTo(0f, -size)
                shapePath.lineTo(size * 0.866f, size * 0.5f)
                shapePath.lineTo(-size * 0.866f, size * 0.5f)
                shapePath.close()
            }
            1 -> {
                shapePath.addRect(-size, -size, size, size, Path.Direction.CW)
            }
            2 -> {
                for (i in 0..5) {
                    val angle = (i * 60f - 90f) * PI.toFloat() / 180f
                    val x = cos(angle) * size
                    val y = sin(angle) * size
                    if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
                }
                shapePath.close()
            }
            3 -> {
                shapePath.moveTo(0f, -size * 1.2f)
                shapePath.lineTo(size * 0.7f, 0f)
                shapePath.lineTo(0f, size * 1.2f)
                shapePath.lineTo(-size * 0.7f, 0f)
                shapePath.close()
            }
        }
        
        canvas.drawPath(shapePath, paint)
    }
}
