#!/usr/bin/env node
/**
 * gen_m3_scheme.mjs —— 用**官方配色算法**（Material Color Utilities / HCT）从一颗种子色
 * 生成整套 M3 / M3E 配色，打印成可以直接粘进 `ui/design/theme/Color.kt` 的 Kotlin。
 *
 * ## 为什么需要它（别手改色值）
 *
 * 官方 Compose 的 `expressiveLightColorScheme()` **并不鲜艳** —— 它只是
 * `lightColorScheme()` 改了 4 个 `on*Container`，其余角色等同基线 M3，而基线用的是
 * **TonalSpot** 类型（色度刻意压低）。想要"鲜艳"，只能换**配色类型**：
 *
 * | 类型 | 特点 |
 * | --- | --- |
 * | `tonal-spot` | 官方默认，低饱和（灰） |
 * | `vibrant` | 高饱和，**保留种子的色相** |
 * | `expressive` | 更活泼、多色相（主色可能换色相） |
 * | `content` / `neutral` / `monochrome` / `fidelity` / `rainbow` / `fruit-salad` | 其余官方类型 |
 *
 * ## 用法
 *
 * ```bash
 * cd tools
 * npm i                 # 装官方算法库 + esbuild（只在这一步需要联网）
 *
 * # 直接就地更新主题文件（推荐：保留文件里的 KDoc 与 appColorScheme，只换两个 val 块）
 * node gen_m3_scheme.mjs --scheme vibrant --seed '#6750A4' \
 *     --in-place ../app/src/main/java/org/dpdns/mfsky/skipad/ui/design/theme/Color.kt
 *
 * # 或者只打印出来，自己比对
 * node gen_m3_scheme.mjs --scheme expressive --seed '#6750A4'
 * ```
 *
 * ⚠️ **鲜艳度的量级**（同一种子 `#6750A4` 实测 primary 的 chroma）：`tonal-spot` 36（灰）、
 * `rainbow` 48（经典 M3 紫，当文字也不刺眼）、`vibrant` 87（电光紫，只适合当色块，
 * **当文字会刺眼**）。要"色块鲜艳 + 文字克制"，只能靠**用色位置**（文字走
 * `onSurface`/`onSurfaceVariant`），不能靠这档数字 —— 官方没有中间档。
 *
 * ## 依赖说明
 *
 * `@material/material-color-utilities` 是 ESM-only 且**内部 import 缺 `.js` 扩展名**
 * （Node 直接 import 会报 ERR_MODULE_NOT_FOUND）。所以本脚本用 `esbuild` 把它打成一个
 * 可用的 `mcu.mjs`（首次或依赖变更时重打，之后复用）。
 */
import { existsSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const HERE = dirname(fileURLToPath(import.meta.url));
const BUNDLE = join(HERE, 'mcu.mjs');
const PKG_ENTRY = join(HERE, 'node_modules', '@material', 'material-color-utilities', 'index.js');

async function ensureBundle() {
  if (existsSync(BUNDLE) && existsSync(PKG_ENTRY) &&
      statSync(BUNDLE).mtimeMs >= statSync(PKG_ENTRY).mtimeMs) {
    return;
  }
  console.error('· 正在打包官方算法库（首次或依赖更新时）…');
  await build({
    entryPoints: [PKG_ENTRY],
    bundle: true,
    format: 'esm',
    platform: 'node',
    outfile: BUNDLE,
    logLevel: 'warning',
  });
}

/** 命令行取值：`--key value`。 */
function arg(key, fallback) {
  const i = process.argv.indexOf(`--${key}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const SCHEMES = {
  'tonal-spot': 'SchemeTonalSpot',
  vibrant: 'SchemeVibrant',
  expressive: 'SchemeExpressive',
  content: 'SchemeContent',
  neutral: 'SchemeNeutral',
  monochrome: 'SchemeMonochrome',
  fidelity: 'SchemeFidelity',
  rainbow: 'SchemeRainbow',
  'fruit-salad': 'SchemeFruitSalad',
};

/** 生成的角色顺序 —— 按"成对出现"排，方便人工核对（primary/onPrimary/…）。 */
const ROLES = [
  'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer',
  'inversePrimary', 'primaryFixed', 'primaryFixedDim',
  'onPrimaryFixed', 'onPrimaryFixedVariant',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'secondaryFixed', 'secondaryFixedDim', 'onSecondaryFixed', 'onSecondaryFixedVariant',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'tertiaryFixed', 'tertiaryFixedDim', 'onTertiaryFixed', 'onTertiaryFixedVariant',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'background', 'onBackground',
  'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant', 'surfaceTint',
  'inverseSurface', 'inverseOnSurface',
  'outline', 'outlineVariant', 'scrim',
  'surfaceBright', 'surfaceDim',
  'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
  'surfaceContainerHigh', 'surfaceContainerHighest',
];

const schemeName = arg('scheme', 'vibrant');
const className = SCHEMES[schemeName];
if (!className) {
  console.error(`未知配色类型 ${schemeName}；可选：${Object.keys(SCHEMES).join(' / ')}`);
  process.exit(1);
}
const seed = arg('seed', '#6750A4');
/** 对比度等级：0 默认 / 0.5 中 / 1.0 高（无障碍用）。 */
const contrast = Number(arg('contrast', '0'));

await ensureBundle();

const mcu = await import(`file://${BUNDLE}`);
const { Hct, argbFromHex, hexFromArgb } = mcu;
const Scheme = mcu[className];
const argb = argbFromHex(seed);

/** 生成一个 `val XxxColorScheme: ColorScheme = lightColorScheme(...)` 代码块。 */
function block(valName, dark) {
  const s = new Scheme(Hct.fromInt(argb), dark, contrast);
  const lines = [
    `val ${valName}: ColorScheme = ${dark ? 'darkColorScheme' : 'lightColorScheme'}(`,
  ];
  for (const role of ROLES) {
    const v = s[role];
    if (v === undefined || v === null) continue;
    lines.push(`    ${role} = Color(0xFF${hexFromArgb(v).slice(1).toUpperCase()}),`);
  }
  lines.push(')');
  return lines.join('\n');
}

const blocks = [block('AppLightColorScheme', false), block('AppDarkColorScheme', true)];

/** 把两个 `val` 块整体替换进已有文件（保留文件里的 KDoc 与 appColorScheme 函数）。 */
function splice(src, valName, text, eol) {
  const re = new RegExp(`val ${valName}: ColorScheme = [\\s\\S]*?${eol}\\)`, 'm');
  if (!re.test(src)) throw new Error(`在目标文件里找不到 ${valName}`);
  return src.replace(re, text.replace(/\n/g, eol));
}

const inPlace = arg('in-place', null);
if (inPlace) {
  const { readFileSync, writeFileSync } = await import('node:fs');
  let src = readFileSync(inPlace, 'utf8');
  const eol = src.includes('\r\n') ? '\r\n' : '\n';
  src = splice(src, 'AppLightColorScheme', blocks[0], eol);
  src = splice(src, 'AppDarkColorScheme', blocks[1], eol);
  writeFileSync(inPlace, src, 'utf8');
  console.error(`· 已就地更新 ${inPlace}`);
} else {
  process.stdout.write(blocks.join('\n\n') + '\n');
}

console.error(`· 配色类型=${schemeName}  种子=${seed}  对比度=${contrast}`);
