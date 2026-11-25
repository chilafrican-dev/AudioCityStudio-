package ug.audiocity.lyrics;

/** Look of a lyric video. Plain fields so it is easy to save and test. */
public final class Style {
    public static final String[] ASPECTS = {
            "9:16 vertical · Reels, TikTok, Shorts, Status",
            "16:9 landscape · YouTube, TV",
            "1:1 square · feed posts",
            "4:5 portrait · Instagram / Facebook feed",
            "21:9 cinematic widescreen",
            "4:3 landscape · classic",
    };
    /** Short labels for the format chips. */
    public static final String[] ASPECT_SHORT = {"9:16", "16:9", "1:1", "4:5", "21:9", "4:3"};
    public static final String[] ANIMATIONS = {
            "Karaoke wipe", "Fade & rise", "Scrolling lyrics", "Typewriter", "Word pop",
            "Neon glow", "Blur focus", "Slide-up words", "Zoom burst", "Letter cascade",
            "Wave", "Cinematic", "Word highlighter",
    };

    /** Bundled fonts (assets/fonts/&lt;file&gt;.ttf, SIL Open Font Licence / Apache 2.0), then the system font and the user's own. */
    public static final String[] FONT_NAMES = {
            "Montserrat", "Inter", "Poppins", "Bebas Neue", "Anton", "Oswald", "Archivo Black", "Raleway", "Space Grotesk",
            "Playfair Display", "DM Serif Display", "Abril Fatface", "Righteous", "Bungee", "Permanent Marker",
            "Pacifico", "Dancing Script", "Great Vibes", "Caveat", "Lobster", "System sans", "Imported font",
    };
    public static final String[] FONT_FILES = {
            "Montserrat", "Inter", "Poppins", "BebasNeue", "Anton", "Oswald", "ArchivoBlack", "Raleway", "SpaceGrotesk",
            "PlayfairDisplay", "DMSerifDisplay", "AbrilFatface", "Righteous", "Bungee", "PermanentMarker",
            "Pacifico", "DancingScript", "GreatVibes", "Caveat", "Lobster", null, null,
    };
    public static final int FONT_SYSTEM = 20, FONT_IMPORTED = 21;
    /** Mapping for projects saved by version 0.1, which stored a font index into a shorter list. */
    static final int[] OLD_FONTS = {0, 2, 5, 9, 8, 16, FONT_IMPORTED};

    public static final String[] BACKGROUNDS = {"Animated gradient", "Solid colour", "Photos", "Cover, blurred"};
    public static final String[] COVER_POSITIONS = {"Left", "Centre", "Right", "Top"};
    public static final String[] COVER_SHAPES = {"Square", "Rounded", "Circle", "Vinyl record"};
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
    public static final String[] PHOTO_FITS = {"Fill the frame (crop)", "Whole photo on blurred background"};
    public static final String[] PHOTO_FILTERS = {"Natural", "Black & white", "Warm", "Cool", "Vintage", "Dramatic"};
    public static final int[] PHOTO_SECONDS = {0, 4, 6, 8, 10, 15, 20};
    public static final String[] PHOTO_SECONDS_NAMES = {"Spread over the song", "4 s each", "6 s each", "8 s each", "10 s each", "15 s each", "20 s each"};
    public static final String[] POSITIONS = {"Centre", "Lower third", "Upper third"};
    public static final String[] QUALITIES = {"720p (fast)", "1080p Full HD", "4K Ultra HD (slow, newer phones)"};
    public static final int[] SWATCHES = {0xffffffff, 0xfffff3d6, 0xffffd166, 0xffffb703, 0xffef476f, 0xffff70a6, 0xff06d6a0, 0xff4cc9f0, 0xffa78bfa, 0xff111111};

    public int aspect = 0, animation = 0, font = 0, background = 0, palette = 0, position = 0, quality = 1;
    public int solid = 0xff111118, textColor = 0xffffffff, highlight = 0xffffd166;
    /** Multiplier for the lyric text size, 0.6–1.6. */
    public float textSize = 1f;
    /** Extra space between letters, in ems (0–0.3). */
    public float letterSpacing = 0f;
    /** Darkening of a photo background, 0–0.85. */
    public float dim = .45f;
    public boolean visualizer = true, titleCard = true, shadow = true, uppercase = false, kenBurns = true, progressBar = true;
    public boolean outline = false, glow = false, box = false, letterbox = false;
    public int photoFit = 0, photoFilter = 0, photoSeconds = 0;
    /** Background photo blur, 0 (sharp) to 1 (very soft). */
    public float photoBlur = 0f;
    /** Square cover photo on top of the background. */
    public boolean cover = false, coverPulse = true, coverCaption = true;
    public int coverPos = 0, coverShape = 1;
    /** Cover size as a share of the frame's short side, 0.25–0.7. */
    public float coverSize = .46f;
    public int fps = 30;

    int shortSide() { return quality == 0 ? 720 : quality == 2 ? 2160 : 1080; }
    /** Output width for the chosen aspect and quality (even numbers, as encoders require). */
    public int width() { int s = shortSide(); switch (aspect) { case 1: return s * 16 / 9; case 4: return s * 21 / 9 & ~1; case 5: return s * 4 / 3; default: return s; } }
    public int height() { int s = shortSide(); switch (aspect) { case 0: return s * 16 / 9; case 3: return s * 5 / 4; default: return s; } }
    /** Width divided by height. */
    public float ratio() { return width() / (float) height(); }
    public boolean landscape() { return width() > height(); }
    public int bitrate() { return quality == 0 ? 5_000_000 : quality == 2 ? 40_000_000 : 12_000_000; }

    public Style copy() {
        Style s = new Style();
        s.aspect = aspect; s.animation = animation; s.font = font; s.background = background; s.palette = palette; s.position = position; s.quality = quality;
        s.solid = solid; s.textColor = textColor; s.highlight = highlight; s.textSize = textSize; s.letterSpacing = letterSpacing; s.dim = dim;
        s.visualizer = visualizer; s.titleCard = titleCard; s.shadow = shadow; s.uppercase = uppercase; s.kenBurns = kenBurns; s.progressBar = progressBar;
        s.outline = outline; s.glow = glow; s.box = box; s.letterbox = letterbox;
        s.photoFit = photoFit; s.photoFilter = photoFilter; s.photoSeconds = photoSeconds; s.fps = fps; s.photoBlur = photoBlur;
        s.cover = cover; s.coverPulse = coverPulse; s.coverCaption = coverCaption; s.coverPos = coverPos; s.coverShape = coverShape; s.coverSize = coverSize;
        return s;
    }

    static int clampIndex(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }
    /** Keeps values from an older or edited project file in range. */
    public void sanitise() {
        aspect = clampIndex(aspect, ASPECTS.length); animation = clampIndex(animation, ANIMATIONS.length); font = clampIndex(font, FONT_NAMES.length);
        background = clampIndex(background, BACKGROUNDS.length); palette = clampIndex(palette, PALETTES.length); position = clampIndex(position, POSITIONS.length);
        quality = clampIndex(quality, QUALITIES.length); photoFit = clampIndex(photoFit, PHOTO_FITS.length); photoFilter = clampIndex(photoFilter, PHOTO_FILTERS.length);
        if (photoSeconds < 0 || photoSeconds > 60) photoSeconds = 0;
        textSize = Math.max(.6f, Math.min(1.6f, Float.isNaN(textSize) ? 1 : textSize)); dim = Math.max(0, Math.min(.85f, Float.isNaN(dim) ? .45f : dim));
        letterSpacing = Math.max(0, Math.min(.3f, Float.isNaN(letterSpacing) ? 0 : letterSpacing));
        if (fps != 24 && fps != 30 && fps != 60) fps = 30;
        photoBlur = Math.max(0, Math.min(1, Float.isNaN(photoBlur) ? 0 : photoBlur));
        coverPos = clampIndex(coverPos, COVER_POSITIONS.length); coverShape = clampIndex(coverShape, COVER_SHAPES.length);
        coverSize = Math.max(.25f, Math.min(.7f, Float.isNaN(coverSize) ? .46f : coverSize));
    }

    static int fontIndex(String name) { for (int i = 0; i < FONT_NAMES.length; i++) if (FONT_NAMES[i].equals(name)) return i; return -1; }

    // ------------------------------------------------------------------ one-tap looks

    public static final String[] PRESETS = {"Clean pop", "Neon night", "Cinematic", "Karaoke classic", "Minimal", "Social captions", "Handwritten", "Bold poster", "Album cover", "Vinyl"};

    /** Applies a ready-made professional look. Keeps the format, photos, quality and colours of the background choice the user made. */
    public void preset(int i) {
        shadow = true; outline = false; glow = false; box = false; uppercase = false; letterSpacing = 0; textSize = 1f; letterbox = false; position = 0;
        textColor = 0xffffffff;
        if (i < 8) { cover = false; if (background == 3) background = 0; }
        switch (i) {
            case 0: font = 0; animation = 7; highlight = 0xffffd166; if (background != 2) { background = 0; palette = 1; } break;
            case 1: font = 12; animation = 5; highlight = 0xffff70a6; textColor = 0xfffff3d6; glow = true; if (background != 2) { background = 0; palette = 3; } break;
            case 2: font = 9; animation = 11; highlight = 0xfffff3d6; letterbox = true; textSize = .9f; position = 1; if (background != 2) { background = 0; palette = 0; } break;
            case 3: font = 4; animation = 0; highlight = 0xffffd166; uppercase = true; textSize = 1.1f; outline = true; if (background != 2) { background = 0; palette = 0; } break;
            case 4: font = 1; animation = 1; highlight = 0xffffffff; shadow = false; letterSpacing = .02f; if (background != 2) { background = 0; palette = 7; } break;
            case 5: font = 2; animation = 12; highlight = 0xffffd166; box = false; position = 1; textSize = .95f; if (background != 2) { background = 0; palette = 2; } break;
            case 6: font = 18; animation = 9; highlight = 0xfff1c6b8; textSize = 1.15f; if (background != 2) { background = 0; palette = 6; } break;
            case 7: font = 3; animation = 8; highlight = 0xffef476f; uppercase = true; textSize = 1.25f; letterSpacing = .04f; if (background != 2) { background = 0; palette = 5; } break;
            // The two cover looks need a cover photo; the app asks for one if missing.
            case 8: font = 2; animation = 7; highlight = 0xffffd166; cover = true; coverShape = 1; coverPos = landscape() ? 0 : 3; coverCaption = true; coverPulse = true; background = 3; photoBlur = Math.max(photoBlur, .6f); dim = .5f; textSize = .9f; break;
            default: font = 0; animation = 0; highlight = 0xffffd166; cover = true; coverShape = 3; coverPos = landscape() ? 0 : 3; coverCaption = true; coverPulse = false; background = 3; photoBlur = Math.max(photoBlur, .7f); dim = .55f; textSize = .9f; break;
        }
    }
}
