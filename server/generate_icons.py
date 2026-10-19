# -*- coding: utf-8 -*-
"""指间 Fingertip 图标生成脚本（v4 · 分端适配）

设计：蓝色指尖涟漪（实心圆点=指尖点击，双圈涟漪=点击波纹/无线信号双关）。

分端适配（v3 全端白底的教训）：
- 手机端（Android）：白底 + 蓝图形。adaptive 前景必须收进中心安全区
  （108dp 画布四边各被 mask 裁 18dp，主体总直径 ≤55dp），v3 的 74.5dp 被裁爆了。
- 电脑端（Windows）：深蓝底 + 白图形。Windows 任务栏/exe 图标不套 mask、
  方形呈现，白底在方块里素空（用户反馈"奇怪"），深蓝底才有存在感。
- 托盘：深蓝圆底 + 白图形简化版，深浅托盘栏都鲜明。

输出：
  1. Android adaptive 前景 drawable-nodpi/ic_launcher_foreground.png (432x432)
  2. Android legacy mipmap-*/ic_launcher.png(白圆角方)、ic_launcher_round.png(白圆)
  3. server/fingertip.ico (16~256 多尺寸，深蓝底)
  4. server/appicon64.png (窗口标题栏 logo，深蓝底)
  5. server/trayicon64.png (托盘，深蓝圆底简化版)
  6. server/_icon_preview.png 场景模拟拼盘（mask 效果/深浅托盘）供检查

运行：.venv\\Scripts\\python.exe generate_icons.py
"""
import os

from PIL import Image, ImageDraw

ROOT = r"D:\work buddy\zhijian"
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
SERVER = os.path.join(ROOT, "server")

BLUE = (10, 132, 255, 255)    # #0A84FF 主题蓝
WHITE = (255, 255, 255, 255)
DARK_GRAY = (48, 48, 52, 255)  # 预览里模拟深色托盘栏
LIGHT_GRAY = (235, 235, 238, 255)  # 预览里模拟浅色托盘栏
SS = 4                        # 超采样倍数（抗锯齿）

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
FOREGROUND_SIZE = 432         # adaptive 108dp @ 4x
SQUIRCLE_RATIO = 0.2237       # 圆角率（近似 iOS squircle）

# 构图参数（相对边长的比例）。圆心一律 0.5 严格居中（v3 偏下 0.52，用户反馈不居中）
ANDROID_FG = dict(dot=0.085, r1=0.155, w1=0.045, r2=0.235, w2=0.038)  # adaptive：总直径 ~55dp，收进安全区
LEGACY = dict(dot=0.105, r1=0.190, w1=0.052, r2=0.290, w2=0.042)      # legacy/ico 大尺寸：无 mask，主体占比 ~62%
TIGHT = dict(dot=0.16, r1=0.26, w1=0.075, r2=0.36, w2=0.055)           # ico 小尺寸（16~32px）：双圈保留+线宽加粗，与设计一致
TRAY = dict(dot=0.13, r1=0.24, w1=0.06)                                # 托盘简化版（去外圈，小尺寸可辨）


def _ring(d, cx, cy, r, w, color, alpha):
    d.ellipse(
        [cx - r, cy - r, cx + r, cy + r],
        outline=(color[0], color[1], color[2], int(255 * alpha)),
        width=max(1, w),
    )


def draw_subject(d: ImageDraw.ImageDraw, s: int, p: dict, color, simple: bool = False) -> None:
    """在 s×s 画布上画指尖+涟漪主体（调用方传 4x 超采样后的实际像素边长）。"""
    cx = cy = s * 0.5
    r = s * p["dot"]
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    _ring(d, cx, cy, s * p["r1"], max(1, round(s * p["w1"])), color, 0.95)
    if not simple and "r2" in p:
        _ring(d, cx, cy, s * p["r2"], max(1, round(s * p["w2"])), color, 0.45)


def make_icon(
    size: int,
    bg: str = "squircle",
    bg_color=WHITE,
    fg_color=BLUE,
    params: dict = LEGACY,
    simple: bool | None = None,
) -> Image.Image:
    """生成 size×size 图标。bg: squircle/circle/none；params 选构图组。"""
    if simple is None:
        simple = size < 64 and "r2" in params
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if bg == "squircle":
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=round(SQUIRCLE_RATIO * s), fill=bg_color)
    elif bg == "circle":
        d.ellipse([0, 0, s - 1, s - 1], fill=bg_color)
    draw_subject(d, s, params, fg_color, simple)
    return img.resize((size, size), Image.LANCZOS)


def apply_mask(icon: Image.Image, shape: str) -> Image.Image:
    """给预览图套 launcher mask（模拟 Android 自适应图标的实际呈现）。"""
    w, h = icon.size
    m = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, w - 1, h - 1], fill=255)
    elif shape == "squircle":
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=round(SQUIRCLE_RATIO * w), fill=255)
    icon.putalpha(m)
    return icon


def main() -> None:
    # 1) Android adaptive 前景：透明底 + 安全区构图（Android 自行套 mask）
    fg = make_icon(FOREGROUND_SIZE, bg="none", fg_color=BLUE, params=ANDROID_FG, simple=False)
    fg_path = os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png")
    fg.save(fg_path)
    print("saved", fg_path)

    # 2) Android legacy 各密度（v26 以下）：白圆角方 + 白圆，蓝图形放大构图
    for folder, size in DENSITIES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        make_icon(size, bg="squircle", bg_color=WHITE, fg_color=BLUE).save(
            os.path.join(d, "ic_launcher.png")
        )
        make_icon(size, bg="circle", bg_color=WHITE, fg_color=BLUE).save(
            os.path.join(d, "ic_launcher_round.png")
        )
        print("saved", folder, size)

    # 3) Windows ico 多尺寸：深蓝底 + 白图形（Windows 不套 mask，深底才有存在感）。
    #    小尺寸（16~32px，任务栏小图标实际取用）用 TIGHT 参数：保留双圈+加粗，
    #    避免 v4 的「48px 以下自动简化成单圈」导致任务栏图标与大图不一致。
    #    注意：Pillow 12 的 ICO _save 以 base 图尺寸为闸门（size > base.size 直接跳过），
    #    必须以最大的 256 为 base、其余 append_images，否则只会写出一个 16x16 项。
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    by_size = {
        sz: make_icon(sz, bg="squircle", bg_color=BLUE, fg_color=WHITE,
                      params=TIGHT if sz < 48 else LEGACY, simple=False)
        for sz in ico_sizes
    }
    ico_path = os.path.join(SERVER, "fingertip.ico")
    by_size[256].save(ico_path, format="ICO",
                      append_images=[by_size[sz] for sz in ico_sizes if sz != 256])
    print("saved", ico_path)

    # 4) 窗口标题栏 logo：深蓝底 + 白图形（favicon 风格，18px 显示）
    make_icon(64, bg="squircle", bg_color=BLUE, fg_color=WHITE).save(
        os.path.join(SERVER, "appicon64.png")
    )
    print("saved appicon64.png")

    # 5) 托盘：深蓝 squircle 底 + 白图形简化版（与整体设计统一为圆角方，
    #    去外圈保 16px 可辨；深浅托盘栏都鲜明）
    make_icon(64, bg="squircle", bg_color=BLUE, fg_color=WHITE, params=TRAY, simple=True).save(
        os.path.join(SERVER, "trayicon64.png")
    )
    print("saved trayicon64.png")

    # 6) 场景模拟拼盘：手机 mask 效果 + 电脑端各场景
    tile = 180
    gap = 14
    cols = 4
    rows = 2
    W = cols * tile + (cols + 1) * gap
    H = rows * tile + (rows + 1) * gap + rows * 22
    preview = Image.new("RGBA", (W, H), (245, 245, 247, 255))
    d = ImageDraw.Draw(preview)

    def cell(x, y, img, label):
        d.rectangle([x - 2, y - 2, x + tile + 1, y + tile + 1], fill=(255, 255, 255, 255))
        preview.alpha_composite(img, (x, y))
        d.text((x + tile // 2, y + tile + 16), label, fill=(90, 90, 95, 255), anchor="mm")

    # 第一行：手机端（白底蓝图形，三种 mask 实际呈现）
    white_bg = make_icon(tile, bg="squircle", bg_color=WHITE, fg_color=BLUE, params=LEGACY)
    cell(gap, gap, apply_mask(white_bg.copy(), "circle").convert("RGBA"), "圆形 mask")
    cell(gap * 2 + tile, gap, apply_mask(white_bg.copy(), "squircle").convert("RGBA"), "圆角方 mask")
    cell(gap * 3 + tile * 2, gap, make_icon(tile, bg="squircle", bg_color=WHITE, fg_color=BLUE), "旧式方形")
    cell(gap * 4 + tile * 3, gap, make_icon(tile, bg="none", fg_color=BLUE, params=ANDROID_FG), "前景层")
    # 第二行：电脑端（深蓝底白图形 + 托盘深浅栏）
    cell(gap, gap * 2 + tile + 22, make_icon(tile, bg="squircle", bg_color=BLUE, fg_color=WHITE), "exe/任务栏")
    cell(gap * 2 + tile, gap * 2 + tile + 22, make_icon(64, bg="squircle", bg_color=BLUE, fg_color=WHITE).resize((tile, tile), Image.LANCZOS), "窗口 logo")
    tray = make_icon(64, bg="squircle", bg_color=BLUE, fg_color=WHITE, params=TRAY, simple=True).resize((tile, tile), Image.LANCZOS)
    dark_cell = Image.new("RGBA", (tile, tile), DARK_GRAY)
    dark_cell.alpha_composite(tray)
    cell(gap * 3 + tile * 2, gap * 2 + tile + 22, dark_cell, "托盘·深色栏")
    light_cell = Image.new("RGBA", (tile, tile), LIGHT_GRAY)
    light_cell.alpha_composite(tray)
    cell(gap * 4 + tile * 3, gap * 2 + tile + 22, light_cell, "托盘·浅色栏")

    prev_path = os.path.join(SERVER, "_icon_preview.png")
    preview.convert("RGB").save(prev_path)
    print("saved", prev_path)


if __name__ == "__main__":
    main()
