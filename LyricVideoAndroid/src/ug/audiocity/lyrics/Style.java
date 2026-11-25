package ug.audiocity.lyrics;

/** Look of a lyric video. Plain fields so it is easy to save and test. */
public final class Style {
    public static final String[] ASPECTS = {"9:16 vertical · Reels, TikTok, Shorts, Status", "16:9 landscape · YouTube", "1:1 square · feed posts"};
    public static final String[] ANIMATIONS = {"Karaoke wipe", "Fade & rise", "Scrolling lyrics", "Typewriter", "Word pop"};
    public static final String[] FONTS = {"Heavy sans", "Bold sans", "Condensed", "Serif", "Mono", "Handwritten", "Imported font"};
    public static final String[] BACKGROUNDS = {"Animated gradient", "Solid colour", "Photo"};
    public static final String[] PALETTE_NAMES = {"Midnight", "Sunset", "Ocean", "Neon", "Forest", "Ember", "Rose gold", "Mono"};
    public static final int[][] PALETTES = {
            {0xff0b0b23, 0xff2b1a5a, 0xff0f4c75},
            {0xff2d0b3a, 0xffc0392b, 0xfff39c12},
            {0xff021b36, 0xff006d77, 0xff83c5be},
            {0xff120024, 0xffff006e, 0xff3a86ff},
            {0xff081c15, 0xff1b4332, 0xff74c69d},
            {0xff1a0500, 0xff7f1d1d, 0xfff97316},
            {0xff2b1b1b, 0xffb76e79, 0xfff1c6b8},
            {0xff050505, 0xff2a2a2a, 0xff5a5a5a},
    };
    public static final String[] POSITIONS = {"Centre", "Lower third", "Upper third"};
    public static final String[] QUALITIES = {"720p (faster)", "1080p (sharper)"};
    public static final int[] SWATCHES = {0xffffffff, 0xfffff3d6, 0xffffd166, 0xffffb703, 0xffef476f, 0xffff70a6, 0xff06d6a0, 0xff4cc9f0, 0xffa78bfa, 0xff111111};

    public int aspect = 0, animation = 0, font = 0, background = 0, palette = 0, position = 0, quality = 1;
    public int solid = 0xff111118, textColor = 0xffffffff, highlight = 0xffffd166;
    /** Multiplier for the lyric text size, 0.6–1.6. */
    public float textSize = 1f;
    /** Darkening of a photo background, 0–0.85. */
    public float dim = .45f;
    public boolean visualizer = true, titleCard = true, shadow = true, uppercase = false, kenBurns = true, progressBar = true;
    public int fps = 30;

    /** Output width for the chosen aspect and quality (even numbers, as encoders require). */
    public int width() { int s = quality == 0 ? 720 : 1080; return aspect == 1 ? s * 16 / 9 : s; }
    public int height() { int s = quality == 0 ? 720 : 1080; return aspect == 0 ? s * 16 / 9 : s; }
    /** Width divided by height. */
    public float ratio() { return aspect == 0 ? 9f / 16 : aspect == 1 ? 16f / 9 : 1f; }
    public int bitrate() { return quality == 0 ? 5_000_000 : 10_000_000; }

    public Style copy() {
        Style s = new Style();
        s.aspect = aspect; s.animation = animation; s.font = font; s.background = background; s.palette = palette; s.position = position; s.quality = quality;
        s.solid = solid; s.textColor = textColor; s.highlight = highlight; s.textSize = textSize; s.dim = dim;
        s.visualizer = visualizer; s.titleCard = titleCard; s.shadow = shadow; s.uppercase = uppercase; s.kenBurns = kenBurns; s.progressBar = progressBar; s.fps = fps;
        return s;
    }

    static int clampIndex(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }
    /** Keeps values from an older or edited project file in range. */
    public void sanitise() {
        aspect = clampIndex(aspect, ASPECTS.length); animation = clampIndex(animation, ANIMATIONS.length); font = clampIndex(font, FONTS.length);
        background = clampIndex(background, BACKGROUNDS.length); palette = clampIndex(palette, PALETTES.length); position = clampIndex(position, POSITIONS.length);
        quality = clampIndex(quality, QUALITIES.length);
        textSize = Math.max(.6f, Math.min(1.6f, Float.isNaN(textSize) ? 1 : textSize)); dim = Math.max(0, Math.min(.85f, Float.isNaN(dim) ? .45f : dim));
        if (fps != 24 && fps != 30 && fps != 60) fps = 30;
    }
}
