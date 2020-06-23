package com.github.tommyettinger.worldly;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.NumberUtils;

import static com.badlogic.gdx.utils.NumberUtils.longBitsToDouble;

/**
 * Created by Tommy Ettinger on 9/6/2019.
 */
public class WorldMapView {
    protected int width, height;
    protected float[][] colorMap;
    protected WorldMapGenerator world;
    protected WorldMapGenerator.DetailedBiomeMapper biomeMapper;

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float[][] getColorMap() {
        return colorMap;
    }

    public WorldMapGenerator.DetailedBiomeMapper getBiomeMapper() {
        return biomeMapper;
    }

    public void setBiomeMapper(WorldMapGenerator.DetailedBiomeMapper biomeMapper) {
        this.biomeMapper = biomeMapper;
    }

    public WorldMapGenerator getWorld() {
        return world;
    }

    public void setWorld(WorldMapGenerator world) {
        this.world = world;
        if(this.width != world.width || this.height != world.height)
        {
            width = world.width;
            height = world.height;
            colorMap = new float[width][height];
        }
    }

    public WorldMapView(WorldMapGenerator worldMapGenerator)
    {
        world = worldMapGenerator == null ? new WorldMapGenerator.LocalMap() : worldMapGenerator;
        width = world.width;
        height = world.height;
        colorMap = new float[width][height];
        this.biomeMapper = new WorldMapGenerator.DetailedBiomeMapper();
        initialize();
    }
    
    public WorldMapView(long seed, int width, int height)
    {
        this(new WorldMapGenerator.LocalMap(seed, width, height));
    }


    public static float floatGet(int r, int g, int b) {
        return NumberUtils.intBitsToFloat((r & 0xff) | (g << 8 & 0xff00) | (b << 16 & 0xff0000)
                | 0xfe000000);
    }

    /**
     * Gets a variation on the packed float color basis as another packed float that has its hue, saturation, value, and
     * opacity adjusted by the specified amounts. Takes floats representing the amounts of change to apply to hue,
     * saturation, value, and opacity; these can be between -1f and 1f. Returns a float that can be used as a packed or
     * encoded color with methods like {@link com.badlogic.gdx.graphics.g2d.Batch#setPackedColor(float)}
     * The float is likely to be different than the result of {@link Color#toFloatBits()} unless hue saturation, value,
     * and opacity are all 0. This won't allocate any objects.
     * <br>
     * The parameters this takes all specify additive changes for a color component, clamping the final values so they
     * can't go above 1 or below 0, with an exception for hue, which can rotate around if lower or higher hues would be
     * used. As an example, if you give this 0.4f for saturation, and the current color has saturation 0.7f, then the
     * resulting color will have 1f for saturation. If you gave this -0.1f for saturation and the current color again
     * has saturation 0.7f, then resulting color will have 0.6f for saturation.
     *
     * @param basis      a packed float color that will be used as the starting point to make the next color
     * @param hue        -1f to 1f, the hue change that can be applied to the new float color (not clamped, wraps)
     * @param saturation -1f to 1f, the saturation change that can be applied to the new float color
     * @param value      -1f to 1f, the value/brightness change that can be applied to the new float color
     * @param opacity    -1f to 1f, the opacity/alpha change that can be applied to the new float color
     * @return a float encoding a variation of basis with the given changes
     */
    public static float toEditedFloat(float basis, float hue, float saturation, float value, float opacity) {
        final int bits = NumberUtils.floatToIntBits(basis);
        final float h, s,
                r = (bits & 0x000000ff) * 0x1.010102p-8f,
                g = (bits & 0x0000ff00) * 0x1.010102p-16f,
                b = (bits & 0x00ff0000) * 0x1.010102p-24f;
        final float min = Math.min(Math.min(r, g), b);   //Min. value of RGB
        final float max = Math.max(Math.max(r, g), b);   //Max value of RGB, equivalent to value
        final float delta = max - min;                   //Delta RGB value
        if ( delta < 0.0039f )                           //This is a gray, no chroma...
        {
            s = 0f;
            h = 0f;
            hue = 1f;
        }
        else                                             //Chromatic data...
        {
            s = delta / max;
            final float rDelta = (((max - r) / 6f) + (delta / 2f)) / delta;
            final float gDelta = (((max - g) / 6f) + (delta / 2f)) / delta;
            final float bDelta = (((max - b) / 6f) + (delta / 2f)) / delta;

            if      (r == max) h = (bDelta - gDelta + 1f) % 1f;
            else if (g == max) h = ((1f / 3f) + rDelta - bDelta + 1f) % 1f;
            else               h = ((2f / 3f) + gDelta - rDelta + 1f) % 1f;
        }
        saturation = MathUtils.clamp(s + saturation, 0f, 1f);
        value = MathUtils.clamp(max + value, 0f, 1f);
        opacity = MathUtils.clamp(((bits & 0xfe000000) >>> 24) * 0x1.020408p-8f + opacity, 0f, 1f);

        if (saturation <= 0.0039f) {
            return Color.toFloatBits(value, value, value, opacity);
        } else if (value <= 0.0039f) {
            return NumberUtils.intBitsToFloat((int) (opacity * 255f) << 24 & 0xFE000000);
        } else {
            final float hu = ((h + hue + 6f) % 1f) * 6f;
            final int i = (int) hu;
            final float x = value * (1 - saturation);
            final float y = value * (1 - saturation * (hu - i));
            final float z = value * (1 - saturation * (1 - (hu - i)));

            switch (i) {
                case 0:
                    return Color.toFloatBits(value, z, x, opacity);
                case 1:
                    return Color.toFloatBits(y, value, x, opacity);
                case 2:
                    return Color.toFloatBits(x, value, z, opacity);
                case 3:
                    return Color.toFloatBits(x, y, value, opacity);
                case 4:
                    return Color.toFloatBits(z, x, value, opacity);
                default:
                    return Color.toFloatBits(value, x, y, opacity);
            }
        }
    }

    /**
     * Interpolates from the packed float color start towards end by change. Both start and end should be packed colors,
     * as from {@link Color#toFloatBits()} or {@link #floatGet(int, int, int)}, and change can be between 0f
     * (keep start) and 1f (only use end). This is a good way to reduce allocations of temporary Colors.
     * @param start the starting color as a packed float
     * @param end the target color as a packed float
     * @param change how much to go from start toward end, as a float between 0 and 1; higher means closer to end
     * @return a packed float that represents a color between start and end
     */
    public static float lerpFloatColors(final float start, final float end, final float change) {
        final int s = NumberUtils.floatToIntBits(start), e = NumberUtils.floatToIntBits(end),
                rs = (s & 0xFF), gs = (s >>> 8) & 0xFF, bs = (s >>> 16) & 0xFF, as = (s >>> 24) & 254,
                re = (e & 0xFF), ge = (e >>> 8) & 0xFF, be = (e >>> 16) & 0xFF, ae = (e >>> 24) & 254;
        return NumberUtils.intBitsToFloat(((int) (rs + change * (re - rs)) & 0xFF)
                | (((int) (gs + change * (ge - gs)) & 0xFF) << 8)
                | (((int) (bs + change * (be - bs)) & 0xFF) << 16)
                | (((int) (as + change * (ae - as)) & 0xFE) << 24));
    }
    
    private static double formCurvedDouble(long start) {
        return    longBitsToDouble((start >>> 12) | 0x3fe0000000000000L)
                + longBitsToDouble(((start *= 0x2545F4914F6CDD1DL) >>> 12) | 0x3fe0000000000000L)
                - longBitsToDouble(((start *= 0x2545F4914F6CDD1DL) >>> 12) | 0x3fe0000000000000L)
                - longBitsToDouble(((start *  0x2545F4914F6CDD1DL) >>> 12) | 0x3fe0000000000000L)
                ;
    }

    public static final int
            Desert                 = 0 ,
            Savanna                = 1 ,
            TropicalRainforest     = 2 ,
            Grassland              = 3 ,
            Woodland               = 4 ,
            SeasonalForest         = 5 ,
            TemperateRainforest    = 6 ,
            BorealForest           = 7 ,
            Tundra                 = 8 ,
            Ice                    = 9 ,
            Beach                  = 10,
            Rocky                  = 11,
            Shallow                = 12,
            Ocean                  = 13,
            Empty                  = 14;

    public static float iceColor = floatGet(240, 248, 255);
    public static float desertColor = floatGet(248, 229, 180);
    public static float savannaColor = floatGet(181, 200, 100);
    public static float tropicalRainforestColor = floatGet(66, 123, 25);
    public static float tundraColor = floatGet(151, 175, 159);
    public static float temperateRainforestColor = floatGet(54, 113, 60);
    public static float grasslandColor = floatGet(169, 185, 105);
    public static float seasonalForestColor = floatGet(100, 158, 75);
    public static float borealForestColor = floatGet(75, 105, 45);
    public static float woodlandColor = floatGet(122, 170, 90);
    public static float rockyColor = floatGet(171, 175, 145);
    public static float beachColor = floatGet(255, 235, 180);
    public static float emptyColor = floatGet(34, 32, 52);

    // water colors
    public static float deepColor =    floatGet(0, 42, 88);
    public static float shallowColor = floatGet(20, 145, 197);
    public static float foamColor =    floatGet(61,  162, 215);

    protected float[] biomeColors = {
            desertColor,
            savannaColor,
            tropicalRainforestColor,
            grasslandColor,
            woodlandColor,
            seasonalForestColor,
            temperateRainforestColor,
            borealForestColor,
            tundraColor,
            iceColor,
            beachColor,
            rockyColor,
            shallowColor,
            deepColor,
            emptyColor
    };

    public final static float[] BIOME_TABLE = {
            //COLDEST   //COLDER      //COLD               //HOT                     //HOTTER                 //HOTTEST
            Ice+0.85f,  Ice+0.65f,    Grassland+0.9f,      Desert+0.75f,             Desert+0.8f,             Desert+0.85f,            //DRYEST
            Ice+0.7f,   Tundra+0.9f,  Grassland+0.6f,      Grassland+0.3f,           Desert+0.65f,            Desert+0.7f,             //DRYER
            Ice+0.55f,  Tundra+0.7f,  Woodland+0.4f,       Woodland+0.6f,            Savanna+0.8f,            Desert+0.6f,             //DRY
            Ice+0.4f,   Tundra+0.5f,  SeasonalForest+0.3f, SeasonalForest+0.5f,      Savanna+0.6f,            Savanna+0.4f,            //WET
            Ice+0.2f,   Tundra+0.3f,  BorealForest+0.35f,  TemperateRainforest+0.4f, TropicalRainforest+0.6f, Savanna+0.2f,            //WETTER
            Ice+0.0f,   BorealForest, BorealForest+0.15f,  TemperateRainforest+0.2f, TropicalRainforest+0.4f, TropicalRainforest+0.2f, //WETTEST
            Rocky+0.9f, Rocky+0.6f,   Beach+0.4f,          Beach+0.55f,              Beach+0.75f,             Beach+0.9f,              //COASTS
            Ice+0.3f,   Shallow+0.9f, Shallow+0.75f,       Shallow+0.6f,             Shallow+0.5f,            Shallow+0.4f,            //RIVERS
            Ice+0.2f,   Shallow+0.9f, Shallow+0.65f,       Shallow+0.5f,             Shallow+0.4f,            Shallow+0.3f,            //LAKES
            Ocean+0.9f, Ocean+0.75f,  Ocean+0.6f,          Ocean+0.45f,              Ocean+0.3f,              Ocean+0.15f,             //OCEANS
            Empty                                                                                                                      //SPACE
    };
    public final float[] BIOME_COLOR_TABLE = new float[61], BIOME_DARK_COLOR_TABLE = new float[61];
    
    public void initialize()
    {
        initialize(0f, 0f, 0f, 1f);
    }
    
    public void initialize(float hue, float saturation, float brightness, float contrast)
    {
        float b, diff;
        for (int i = 0; i < 60; i++) {
            b = BIOME_TABLE[i];
            diff = ((b % 1.0f) - 0.48f) * 0.27f * contrast;
            BIOME_COLOR_TABLE[i] = b = toEditedFloat(biomeColors[(int)b], hue, saturation, brightness + diff, 0f);
            BIOME_DARK_COLOR_TABLE[i] = toEditedFloat(b, 0f, 0f, -0.08f, 0f);
        }
        BIOME_COLOR_TABLE[60] = BIOME_DARK_COLOR_TABLE[60] = emptyColor;
    }

    /**
     * Initializes the colors to use for each biome (these are almost always mixed with other biome colors in practice).
     * Each parameter may be null to use the default for an Earth-like world; otherwise it should be a libGDX
     * {@link Color} or some subclass. All non-null parameters should probably be fully opaque,
     * except {@code emptyColor}, which is only used for world maps that show empty space (like a globe, as produced by
     * {@link WorldMapGenerator.RotatingSpaceMap}).
     * @param desertColor hot, dry, barren land; may be sandy, but many real-world deserts don't have much sand
     * @param savannaColor hot, mostly-dry land with some parched vegetation; also called scrub or chaparral
     * @param tropicalRainforestColor hot, extremely wet forests with dense rich vegetation
     * @param grasslandColor prairies that are dry and usually wind-swept, but not especially hot or cold
     * @param woodlandColor part-way between a prairie and a forest; not especially hot or cold
     * @param seasonalForestColor forest that becomes barren in winter (deciduous trees); not especially hot or cold
     * @param temperateRainforestColor forest that tends to be slightly warm but very wet
     * @param borealForestColor forest that tends to be cold and very wet
     * @param tundraColor very cold plains that still have some low-lying vegetation; also called taiga 
     * @param iceColor cold barren land covered in permafrost; also used for rivers and lakes that are frozen
     * @param beachColor sandy or otherwise light-colored shorelines; here, these are more common in warmer places
     * @param rockyColor rocky or otherwise rugged shorelines; here, these are more common in colder places
     * @param shallowColor the color of very shallow water; will be mixed with {@code deepColor} to get most ocean colors
     * @param deepColor the color of very deep water; will be mixed with {@code shallowColor} to get most ocean colors
     * @param emptyColor the color used for empty space off the edge of the world map; may be transparent
     */
    public void initialize(
            Color desertColor,
            Color savannaColor,
            Color tropicalRainforestColor,
            Color grasslandColor,
            Color woodlandColor,
            Color seasonalForestColor,
            Color temperateRainforestColor,
            Color borealForestColor,
            Color tundraColor,
            Color iceColor,
            Color beachColor,
            Color rockyColor,
            Color shallowColor,
            Color deepColor,
            Color emptyColor
    )
    {
        biomeColors[ 0] = desertColor == null ? WorldMapView.desertColor : desertColor.toFloatBits();
        biomeColors[ 1] = savannaColor == null ? WorldMapView.savannaColor : savannaColor.toFloatBits();
        biomeColors[ 2] = tropicalRainforestColor == null ? WorldMapView.tropicalRainforestColor : tropicalRainforestColor.toFloatBits();
        biomeColors[ 3] = grasslandColor == null ? WorldMapView.grasslandColor : grasslandColor.toFloatBits();
        biomeColors[ 4] = woodlandColor == null ? WorldMapView.woodlandColor : woodlandColor.toFloatBits();
        biomeColors[ 5] = seasonalForestColor == null ? WorldMapView.seasonalForestColor : seasonalForestColor.toFloatBits();
        biomeColors[ 6] = temperateRainforestColor == null ? WorldMapView.temperateRainforestColor : temperateRainforestColor.toFloatBits();
        biomeColors[ 7] = borealForestColor == null ? WorldMapView.borealForestColor : borealForestColor.toFloatBits();
        biomeColors[ 8] = tundraColor == null ? WorldMapView.tundraColor : tundraColor.toFloatBits();
        biomeColors[ 9] = iceColor == null ? WorldMapView.iceColor : iceColor.toFloatBits();
        biomeColors[10] = beachColor == null ? WorldMapView.beachColor : beachColor.toFloatBits();
        biomeColors[11] = rockyColor == null ? WorldMapView.rockyColor : rockyColor.toFloatBits();
        biomeColors[12] = shallowColor == null ? WorldMapView.shallowColor : shallowColor.toFloatBits();
        biomeColors[13] = deepColor == null ? WorldMapView.deepColor : deepColor.toFloatBits();
        biomeColors[14] = emptyColor == null ? WorldMapView.emptyColor : emptyColor.toFloatBits();
        float b, diff;
        for (int i = 0; i < 60; i++) {
            b = BIOME_TABLE[i];
            diff = ((b % 1.0f) - 0.48f) * 0.27f;
            BIOME_COLOR_TABLE[i] = b = toEditedFloat(biomeColors[(int)b], 0f, 0f, diff, 0f);
            BIOME_DARK_COLOR_TABLE[i] = toEditedFloat(b, 0f, 0f, -0.08f, 0f);
        }
        BIOME_COLOR_TABLE[60] = BIOME_DARK_COLOR_TABLE[60] = biomeColors[14];
        biomeColors[ 0] = WorldMapView.desertColor;
        biomeColors[ 1] = WorldMapView.savannaColor;
        biomeColors[ 2] = WorldMapView.tropicalRainforestColor;
        biomeColors[ 3] = WorldMapView.grasslandColor;
        biomeColors[ 4] = WorldMapView.woodlandColor;
        biomeColors[ 5] = WorldMapView.seasonalForestColor;
        biomeColors[ 6] = WorldMapView.temperateRainforestColor;
        biomeColors[ 7] = WorldMapView.borealForestColor;
        biomeColors[ 8] = WorldMapView.tundraColor;
        biomeColors[ 9] = WorldMapView.iceColor;
        biomeColors[10] = WorldMapView.beachColor;
        biomeColors[11] = WorldMapView.rockyColor;
        biomeColors[12] = WorldMapView.shallowColor;
        biomeColors[13] = WorldMapView.deepColor;
        biomeColors[14] = WorldMapView.emptyColor;
    }

    /**
     * Initializes the colors to use in some combination for all biomes, without regard for what the biome really is.
     * There should be at least one packed float color given in similarColors, but there can be many of them.
     * @param similarColors an array or vararg of packed float colors with at least one element
     */
    public void match(
            float... similarColors
    )
    {
        for (int i = 0; i < 14; i++) {
            biomeColors[i] = lerpFloatColors(similarColors[i % similarColors.length], similarColors[(i * 5 + 3) % similarColors.length], 0.5f);
        }
        biomeColors[14] = WorldMapView.emptyColor;
        float b, diff;
        for (int i = 0; i < 60; i++) {
            b = BIOME_TABLE[i];
            diff = ((b % 1.0f) - 0.48f) * 0.27f;
            BIOME_COLOR_TABLE[i] = b = toEditedFloat(biomeColors[(int)b], 0f, 0f, diff, 0f);
            BIOME_DARK_COLOR_TABLE[i] = toEditedFloat(b, 0f, 0f, -0.08f, 0f);
        }
        BIOME_COLOR_TABLE[60] = BIOME_DARK_COLOR_TABLE[60] = biomeColors[14];
        biomeColors[ 0] = WorldMapView.desertColor;
        biomeColors[ 1] = WorldMapView.savannaColor;
        biomeColors[ 2] = WorldMapView.tropicalRainforestColor;
        biomeColors[ 3] = WorldMapView.grasslandColor;
        biomeColors[ 4] = WorldMapView.woodlandColor;
        biomeColors[ 5] = WorldMapView.seasonalForestColor;
        biomeColors[ 6] = WorldMapView.temperateRainforestColor;
        biomeColors[ 7] = WorldMapView.borealForestColor;
        biomeColors[ 8] = WorldMapView.tundraColor;
        biomeColors[ 9] = WorldMapView.iceColor;
        biomeColors[10] = WorldMapView.beachColor;
        biomeColors[11] = WorldMapView.rockyColor;
        biomeColors[12] = WorldMapView.shallowColor;
        biomeColors[13] = WorldMapView.deepColor;
        biomeColors[14] = WorldMapView.emptyColor;
    }

    public void generate()
    {
//        generate(world.seedA, world.seedB, 0.9 + NumberTools.formCurvedDouble((world.seedA ^ 0x123456789ABCDL) * 0x12345689ABL ^ world.seedB) * 0.15,
//                DiverRNG.determineDouble(world.seedB * 0x12345L + 0x54321L ^ world.seedA) * 0.2 + 1.0);
        generate(world.seedA, world.seedB, 1.0 + formCurvedDouble((world.seedA ^ 0x123456789ABCDL) * 0x12345689ABL ^ world.seedB) * 0.25,
                SilkRNG.determineDouble(world.seedB * 0x12345 + 0x54321 ^ world.seedA) * 0.25 + 1.0);
    }
    public void generate(double landMod, double heatMod)
    {
        generate(world.seedA, world.seedB, landMod, heatMod);
    }
    
    public void generate(int seedA, int seedB, double landMod, double heatMod) {
        long seed = (long) seedB << 32 | (seedA & 0xFFFFFFFFL);
        world.generate(landMod, heatMod, seed);
        biomeMapper.makeBiomes(world);
    }
    public float[][] show()
    {
        int hc, tc, bc;
        final int[][] heightCodeData = world.heightCodeData;
        double[][] heightData = world.heightData;
        int[][] heatCodeData = biomeMapper.heatCodeData;
        int[][] biomeCodeData = biomeMapper.biomeCodeData;

        for (int y = 0; y < height; y++) {
            PER_CELL:
            for (int x = 0; x < width; x++) {
                hc = heightCodeData[x][y];
                if(hc == 1000)
                {
                    colorMap[x][y] = emptyColor;
                    continue;
                }
                tc = heatCodeData[x][y];
                bc = biomeCodeData[x][y];
                if(tc == 0)
                {
                    switch (hc)
                    {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            colorMap[x][y] = lerpFloatColors(BIOME_COLOR_TABLE[50], BIOME_COLOR_TABLE[12],
                                    (float) ((heightData[x][y] - -1.0) / (WorldMapGenerator.sandLower - -1.0)));
                            continue PER_CELL;
                        case 4:
                            colorMap[x][y] = lerpFloatColors(BIOME_COLOR_TABLE[0], BIOME_COLOR_TABLE[12],
                                    (float) ((heightData[x][y] - WorldMapGenerator.sandLower) / (WorldMapGenerator.sandUpper - WorldMapGenerator.sandLower)));
                            continue PER_CELL;
                    }
                }
                switch (hc) {
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                        colorMap[x][y] = lerpFloatColors(
                                BIOME_COLOR_TABLE[56], BIOME_COLOR_TABLE[43],
                                (MathUtils.clamp((float) (((heightData[x][y] + 0.06) * 8.0) / (WorldMapGenerator.sandLower + 1.0)), 0f, 1f)));
                        break;
                    default:
                        colorMap[x][y] = lerpFloatColors(BIOME_COLOR_TABLE[biomeMapper.extractPartB(bc)],
                                BIOME_DARK_COLOR_TABLE[biomeMapper.extractPartA(bc)], biomeMapper.extractMixAmount(bc));
                }
            }
        }
        
        return colorMap;
    }
}
