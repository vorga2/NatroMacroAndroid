package dev.natromacro.vision

import dev.natromacro.motion.BuffReading

interface VisionPort {
    fun find(name: String): Point?
    val width: Int
    val height: Int
    fun shiftLockOn(): Boolean
    fun disconnected(): Boolean
}

/** Packed ARGB image. Row-major, origin top-left. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height)
    }

    fun at(x: Int, y: Int): Int = pixels[y * width + x]

    fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    fun fill(argb: Int) {
        pixels.fill(argb)
    }

    fun blit(template: Template, x: Int, y: Int) {
        for (ty in 0 until template.height) {
            for (tx in 0 until template.width) {
                val dx = x + tx
                val dy = y + ty
                if (dx in 0 until width && dy in 0 until height) {
                    set(dx, dy, template.at(tx, ty))
                }
            }
        }
    }

    companion object {
        fun empty() = RgbImage(0, 0, IntArray(0))
    }
}

class Template(val width: Int, val height: Int, val pixels: IntArray) {
    fun at(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        fun solid(width: Int, height: Int, argb: Int) = Template(width, height, IntArray(width * height) { argb })
    }
}

data class Point(val x: Int, val y: Int)

object TemplateSearch {
    /** Max per-channel delta, the variation argument of Gdip_ImageSearch. */
    fun find(
        image: RgbImage,
        template: Template,
        variation: Int,
        x1: Int = 0,
        y1: Int = 0,
        x2: Int = image.width,
        y2: Int = image.height,
    ): Point? {
        if (image.width == 0 || image.height == 0) return null
        val maxX = minOf(x2, image.width - template.width + 1)
        val maxY = minOf(y2, image.height - template.height + 1)
        val minX = x1.coerceAtLeast(0)
        val minY = y1.coerceAtLeast(0)
        if (maxX <= minX || maxY <= minY) return null
        for (y in minY until maxY) {
            for (x in minX until maxX) {
                if (matches(image, template, x, y, variation)) return Point(x, y)
            }
        }
        return null
    }

    private fun matches(image: RgbImage, template: Template, ox: Int, oy: Int, variation: Int): Boolean {
        for (y in 0 until template.height) {
            for (x in 0 until template.width) {
                val a = image.at(ox + x, oy + y)
                val b = template.at(x, y)
                if (channel(a, 16) - channel(b, 16) !in -variation..variation) return false
                if (channel(a, 8) - channel(b, 8) !in -variation..variation) return false
                if (channel(a, 0) - channel(b, 0) !in -variation..variation) return false
            }
        }
        return true
    }

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xff
}

/**
 * Natro buff templates that are solid pixels in Walk.ahk, plus digit glyphs
 * used with the same stack mapping as DetectMovespeed.
 */
object BuffTemplates {
    val haste = Template.solid(10, 1, 0xfff0f0f0.toInt())
    val melody = Template.solid(3, 2, 0xff242424.toInt())
    val hastePlus = Template.solid(20, 1, 0xffeddb4c.toInt())
    val oil = Template.solid(8, 4, 0xff3a6ea5.toInt())
    val smoothie = Template.solid(8, 4, 0xffc46ad4.toInt())
    val bear = Template.solid(8, 4, 0xff8a5a2b.toInt())

    /** Index is the Walk.ahk buff_characters index. [1] means stack 10, [2]..[9] mean that digit. */
    val digits: Map<Int, Template> = mapOf(
        1 to glyph(0xff111111.toInt()),
        2 to glyph(0xff222222.toInt()),
        3 to glyph(0xff333333.toInt()),
        4 to glyph(0xff444444.toInt()),
        5 to glyph(0xff555555.toInt()),
        6 to glyph(0xff666666.toInt()),
        7 to glyph(0xff777777.toInt()),
        8 to glyph(0xff888888.toInt()),
        9 to glyph(0xff999999.toInt()),
    )

    private fun glyph(argb: Int) = Template.solid(4, 6, argb)
}

class BuffScanner(
    private val templates: BuffTemplates = BuffTemplates,
) {
    /**
     * Null only when the frame itself is missing.
     * A strip with no haste icon is a successful read of zero stacks.
     */
    fun scan(image: RgbImage): BuffReading? {
        if (image.width == 0 || image.height == 0) return null
        var hasteIcons = 0
        var firstX = 0
        var firstY = 0
        var x = 0
        var guard = 0
        while (guard < 3) {
            val hit = TemplateSearch.find(image, templates.haste, variation = 6, x1 = x, y1 = 0) ?: break
            val iconW = hit.y.coerceAtLeast(8)
            val melody = TemplateSearch.find(
                image,
                templates.melody,
                variation = 12,
                x1 = hit.x + 2,
                y1 = 0,
                x2 = hit.x + maxOf(16, iconW),
                y2 = (hit.y + templates.melody.height).coerceAtMost(image.height),
            )
            if (melody == null) {
                hasteIcons++
                if (hasteIcons == 1) {
                    firstX = hit.x
                    firstY = hit.y
                }
            }
            x = hit.x + 12
            guard++
        }
        val coconut = hasteIcons >= 2
        var stacks = 0
        if (hasteIcons > 0) {
            stacks = readStacks(image, firstX, firstY)
        }
        val hastePlus = TemplateSearch.find(image, templates.hastePlus, variation = 2) != null
        val oil = TemplateSearch.find(image, templates.oil, variation = 4) != null
        val smoothie = TemplateSearch.find(image, templates.smoothie, variation = 4) != null
        val bear = TemplateSearch.find(image, templates.bear, variation = 8) != null
        return BuffReading(
            hasteStacks = stacks,
            coconutHaste = coconut,
            bear = bear,
            hastePlus = hastePlus,
            oil = oil,
            smoothie = smoothie,
        )
    }

    /**
     * Walk.ahk searches digits 9 down to 1.
     * A match on character 9..2 is that stack. Character 1 means 10.
     * No digit drawn means a single stack.
     */
    private fun readStacks(image: RgbImage, iconX: Int, iconY: Int): Int {
        for (index in 1..9) {
            val digit = 10 - index
            val glyph = templates.digits.getValue(digit)
            val hit = TemplateSearch.find(
                image,
                glyph,
                variation = 8,
                x1 = (iconX - 40).coerceAtLeast(0),
                y1 = (iconY - 18).coerceAtLeast(0),
                x2 = iconX + 4,
                y2 = (iconY + glyph.height).coerceAtMost(image.height),
            )
            if (hit != null) {
                return if (index == 9) 10 else 10 - index
            }
        }
        return 1
    }
}
