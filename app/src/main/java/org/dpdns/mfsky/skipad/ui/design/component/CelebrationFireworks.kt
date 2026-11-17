package org.dpdns.mfsky.skipad.ui.design.component

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.dpdns.mfsky.skipad.ui.design.theme.AppCelebrationColors

/**
 * 庆祝撒花 —— 处理全部成功后铺满整屏放一场（全应用只此一处）。
 *
 * ## 性能纪律（60fps 下必须守）
 *
 * 1. 粒子是可变对象、只在出生时 new 一次，每帧只改字段；死亡用 swap-remove，
 *    整场动画**零分配**。
 * 2. 重绘靠"帧时钟"状态（`clockMs`）：失效只发生在绘制阶段，不触发重组。
 * 3. 物理量按屏幕高度缩放，换机型观感一致。
 *
 * ⚠️ 粒子总数是常数（[SHOTS] 各发 count 之和），不能改成"按时间无限发射" ——
 *    那样在真机上会 OOM。
 *
 * ## 其它纪律
 *
 * - **系统关了动画就整段跳过**（开发者选项/无障碍的"移除动画"会把
 *   `ANIMATOR_DURATION_SCALE` 置 0）—— 对前庭敏感的用户尤其重要，别硬播。
 * - **对读屏隐身**：纯装饰，信息（"处理完成"）已由状态卡讲清楚，
 *   画布挂 `clearAndSetSemantics {}`。
 * - **收尾由自己计时**：循环跑到 [CELEBRATION_MS] 就回调 [onFinished]，
 *   由调用方把状态复位（不复位就不会有下一次）。
 * - ⚠️ `dt` **必须封顶**（[MAX_DT_SEC]）：应用切后台再回来时帧间隔可能是几秒，
 *   不封顶的话一帧就把粒子甩出屏幕。
 *
 * @param play true = 开始一场（调用方在"全部成功"或手动触发时置位）。
 * @param onFinished 结束回调：**调用方必须把 [play] 复位**。
 */
@Composable
fun CelebrationFireworks(
    play: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!play) return

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // 「移除动画」= 不做动画。查一次足够：中途改系统设置不需要重放。
    val animationsDisabled = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    // 粒子池：**复用同一批可变对象**，整场动画零分配 —— 这既是帧率的关键，
    // 也是"绝不可能 OOM"的根本保证（数量被 [SHOTS] 的 count 之和封死）。
    val particles = remember { ArrayList<Confetti>(1024) }
    val palette = remember { AppCelebrationColors }
    val random = remember { Random(System.nanoTime()) }

    // 画布尺寸：重力与初速都按屏幕高度缩放，换机型观感一致。
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // ⚠️ 帧时钟 = **重绘开关**。绘制 lambda 里读它（真心用来算淡出），
    //    Compose 于是只失效"绘制"阶段，不触发重组。
    var clockMs by remember { mutableFloatStateOf(0f) }

    val minSize = with(density) { PARTICLE_MIN_DP.dp.toPx() }
    val maxSize = with(density) { PARTICLE_MAX_DP.dp.toPx() }

    if (animationsDisabled) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    LaunchedEffect(Unit) {
        // 起爆时给一次轻震动：手上有反馈，画面才不会"突然出现"。
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

        val emitted = IntArray(SHOTS.size)
        var startNanos = 0L
        var lastNanos = 0L

        while (true) {
            val nowNanos = withFrameNanos { it }
            if (startNanos == 0L) {
                startNanos = nowNanos
                lastNanos = nowNanos
            }
            val elapsedMs = (nowNanos - startNanos) / 1_000_000f
            if (elapsedMs >= CELEBRATION_MS) break

            val dtSec = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
            lastNanos = nowNanos

            val width = canvasSize.width.toFloat()
            val height = canvasSize.height.toFloat()

            // ① 发射：每发按自己的时间线"在 [Shot.emitMs] 里细水长流地"喷出来，
            //    而不是一瞬间全出 —— 后者看着像贴图，不像撒出来。
            if (width > 0f && height > 0f) {
                SHOTS.forEachIndexed { index, shot ->
                    if (elapsedMs >= shot.atMs) {
                        val progress = ((elapsedMs - shot.atMs) / shot.emitMs).coerceIn(0f, 1f)
                        val target = (progress * shot.count).toInt()
                        while (emitted[index] < target) {
                            particles += Confetti.spawn(
                                shot = shot,
                                nowMs = elapsedMs,
                                width = width,
                                height = height,
                                minSize = minSize,
                                maxSize = maxSize,
                                palette = palette,
                                random = random,
                            )
                            emitted[index]++
                        }
                    }
                }
            }

            // ② 物理：重力 + 空气阻力，都按秒积分（掉帧也不会突然加速）。
            val gravity = GRAVITY_REL * height
            val drag = (1f - DRAG_PER_SEC * dtSec).coerceIn(0f, 1f)
            for (index in particles.indices) {
                val particle = particles[index]
                particle.vx *= drag
                particle.vy = particle.vy * drag + gravity * dtSec
                particle.x += particle.vx * dtSec
                particle.y += particle.vy * dtSec
                particle.rotation += particle.rotationSpeed * dtSec
            }

            // ③ 回收：swap-remove（就地、零分配），不留死粒子占绘制时间。
            var index = 0
            while (index < particles.size) {
                if (elapsedMs - particles[index].bornMs >= particles[index].lifeMs) {
                    val last = particles.size - 1
                    particles[index] = particles[last]
                    particles.removeAt(last)
                } else {
                    index++
                }
            }

            clockMs = elapsedMs
        }

        particles.clear()
        onFinished()
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .clearAndSetSemantics {},
    ) {
        val nowMs = clockMs
        for (index in particles.indices) {
            val particle = particles[index]
            val ageMs = nowMs - particle.bornMs
            val lifeLeft = particle.lifeMs - ageMs
            if (ageMs < 0f || lifeLeft <= 0f) continue
            // 淡入 [FADE_IN_MS]、淡出 [FADE_OUT_MS]，中间满不透明。
            val alpha = (lifeLeft / FADE_OUT_MS).coerceAtMost(1f) *
                (ageMs / FADE_IN_MS).coerceAtMost(1f)
            if (alpha <= 0.01f) continue

            if (particle.isRect) {
                // 纸屑：绕自身旋转（这是"撒花"活起来的关键）。
                rotate(degrees = particle.rotation, pivot = Offset(particle.x, particle.y)) {
                    drawRect(
                        color = particle.color,
                        topLeft = Offset(particle.x - particle.size / 2f, particle.y - particle.size * 0.3f),
                        size = Size(particle.size, particle.size * 0.6f),
                        alpha = alpha,
                    )
                }
            } else {
                drawCircle(
                    color = particle.color,
                    radius = particle.size / 2f,
                    center = Offset(particle.x, particle.y),
                    alpha = alpha,
                )
            }
        }
    }
}

/**
 * 一颗纸屑。
 *
 * ⚠️ **可变对象**：一个粒子一辈子只 new 一次，之后每帧只改字段 ——
 * 60fps 下"每帧重建粒子列表"是 GC 抖动的常见来源，这里刻意避开。
 */
private class Confetti(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float,
    val color: Color,
    val isRect: Boolean,
    var rotation: Float,
    val rotationSpeed: Float,
    val bornMs: Float,
    val lifeMs: Float,
) {
    companion object {
        /** 按一发 [shot] 的参数随机生成一颗粒子（方向 / 速度 / 大小 / 颜色 / 寿命都带抖动）。 */
        fun spawn(
            shot: Shot,
            nowMs: Float,
            width: Float,
            height: Float,
            minSize: Float,
            maxSize: Float,
            palette: List<Color>,
            random: Random,
        ): Confetti {
            val angle = Math.toRadians(
                (shot.dirDeg + (random.nextFloat() - 0.5f) * shot.spreadDeg).toDouble(),
            )
            val speed = height * shot.speedRel *
                (1f - shot.speedJitter / 2f + random.nextFloat() * shot.speedJitter)
            return Confetti(
                x = shot.xRel * width,
                y = shot.yRel * height,
                vx = (cos(angle) * speed).toFloat(),
                vy = (sin(angle) * speed).toFloat(),
                size = minSize + random.nextFloat() * (maxSize - minSize),
                color = palette[random.nextInt(palette.size)],
                // 六成矩形（纸屑）、四成圆点 —— 全是一种形状会显得单调。
                isRect = random.nextFloat() < 0.6f,
                rotation = random.nextFloat() * 360f,
                rotationSpeed = (if (random.nextBoolean()) -1f else 1f) * (160f + random.nextFloat() * 420f),
                bornMs = nowMs,
                lifeMs = MIN_LIFE_MS + random.nextFloat() * (MAX_LIFE_MS - MIN_LIFE_MS),
            )
        }
    }
}

/**
 * 一发：什么时候、从哪儿、朝哪儿、喷多少、喷多久。
 *
 * ⚠️ [speedRel] 的单位是**每秒几个屏高**（不是 px/s）—— 物理量按屏幕高度缩放，
 * 换机型观感一致，不用为每台设备调参。
 *
 * ⚠️ [count] 是**这一发的上限**，粒子总数 = 各发 count 之和 —— 常数，
 * 所以不可能出现"无限刷粒子"那类事故（见组件 KDoc 里那次 OOM）。
 */
private class Shot(
    val atMs: Float,
    val emitMs: Float,
    val xRel: Float,
    val yRel: Float,
    val dirDeg: Float,
    val spreadDeg: Float,
    val speedRel: Float,
    val speedJitter: Float,
    val count: Int,
)

/**
 * 时间线：七发。
 *
 * 屏幕坐标里 **-90° = 正上**，所以"朝上斜射的炮"是 -68°（偏右）与 -112°（偏左）；
 * 爆开用 `spreadDeg = 360`（整圆，此时 `dirDeg` 无意义）。
 */
private val SHOTS: List<Shot> = listOf(
    // 第 1、2 发：下沿两角的炮，朝中间上方斜射。
    Shot(atMs = 0f, emitMs = 150f, xRel = 0.04f, yRel = 1.0f, dirDeg = -68f, spreadDeg = 34f, speedRel = 0.85f, speedJitter = 0.35f, count = 120),
    Shot(atMs = 80f, emitMs = 150f, xRel = 0.96f, yRel = 1.0f, dirDeg = -112f, spreadDeg = 34f, speedRel = 0.85f, speedJitter = 0.35f, count = 120),
    // 第 3 发：半空炸开一朵。
    Shot(atMs = 650f, emitMs = 90f, xRel = 0.30f, yRel = 0.30f, dirDeg = -90f, spreadDeg = 360f, speedRel = 0.32f, speedJitter = 0.5f, count = 120),
    // 第 4、5 发：第二轮炮（速度略低，落点更靠中间）。
    Shot(atMs = 1250f, emitMs = 150f, xRel = 0.04f, yRel = 1.0f, dirDeg = -74f, spreadDeg = 40f, speedRel = 0.72f, speedJitter = 0.35f, count = 120),
    Shot(atMs = 1330f, emitMs = 150f, xRel = 0.96f, yRel = 1.0f, dirDeg = -106f, spreadDeg = 40f, speedRel = 0.72f, speedJitter = 0.35f, count = 120),
    // 第 6 发：右上方再炸一朵。
    Shot(atMs = 1800f, emitMs = 90f, xRel = 0.72f, yRel = 0.20f, dirDeg = -90f, spreadDeg = 360f, speedRel = 0.30f, speedJitter = 0.5f, count = 120),
    // 第 7 发：收尾一朵，把"久一点"垫满。
    Shot(atMs = 2400f, emitMs = 90f, xRel = 0.50f, yRel = 0.36f, dirDeg = -90f, spreadDeg = 360f, speedRel = 0.28f, speedJitter = 0.5f, count = 110),
)

/** 整场演出时长（毫秒）。⚠️ 必须晚于"最后一发的出生时间 + 最长寿命"。 */
private const val CELEBRATION_MS = 5600f

/** 重力加速度：每秒增加 [GRAVITY_REL] 个屏高的速度。 */
private const val GRAVITY_REL = 0.55f

/** 空气阻力：每秒衰减掉的比例（让粒子有"飘"的减速，而不是匀速直线）。 */
private const val DRAG_PER_SEC = 1.1f

/** 单帧最大积分步长 —— 切后台回来时帧间隔可能是几秒，不封顶一帧就把粒子甩出屏幕。 */
private const val MAX_DT_SEC = 0.05f

/** 淡入时长（毫秒）：让粒子"出现"而不是"啪地弹出来"。 */
private const val FADE_IN_MS = 90f

/** 淡出时长（毫秒）。 */
private const val FADE_OUT_MS = 700f

/** 粒子寿命范围（毫秒）。上限必须满足"最后一发的出生 + 上限 < [CELEBRATION_MS]"。 */
private const val MIN_LIFE_MS = 2200f
private const val MAX_LIFE_MS = 2900f

/** 纸屑边长范围（dp）。 */
private const val PARTICLE_MIN_DP = 9
private const val PARTICLE_MAX_DP = 18
