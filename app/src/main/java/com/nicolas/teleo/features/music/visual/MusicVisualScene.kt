package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.MusicVisualFrame

interface MusicRenderContext {
    val width: Float
    val height: Float
    fun drawParticle(particle: MusicParticle)
}

interface MusicVisualScene {
    fun onEnter(positionMs: Long)
    fun update(frame: MusicVisualFrame)
    fun render(renderContext: MusicRenderContext)
    fun onExit()
}

class ParticleMusicVisualScene(
    private val engine: MusicVisualEngine
) : MusicVisualScene {
    override fun onEnter(positionMs: Long) = engine.reset(positionMs)

    override fun update(frame: MusicVisualFrame) {
        engine.setSettings(frame.settings)
        engine.update(frame.playbackPositionMs, frame.deltaTimeSeconds, frame.features, frame.activeEvents)
    }

    override fun render(renderContext: MusicRenderContext) {
        engine.particles().forEach(renderContext::drawParticle)
    }

    override fun onExit() = engine.reset(0)
}
