package com.github.tommyettinger.worldly;

import com.badlogic.gdx.utils.IntArray;
import make.some.noise.Noise;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Can be used to generate world maps with a wide variety of data, starting with height, temperature and moisture.
 * From there, you can determine biome information in as much detail as your game needs, with default implementations
 * available; one assigns a single biome to each cell based on heat/moisture, and the other gives a gradient between two
 * biome types for every cell. The maps this produces with {@link SphereMap} are valid for spherical world projections.
 * Because of how the noise this uses works, this also allows extremely high zoom levels for all types of map as long as
 * certain parameters are within reason. Other world maps produce more conventional shapes, like {@link SpaceViewMap}
 * and {@link RotatingSpaceMap} make a view of a marble-like world from space, and others make more unconventional
 * shapes, like {@link EllipticalMap} or {@link EllipticalHammerMap}, which form a 2:1 ellipse shape that accurately
 * keeps sizes but not relative shapes, and {@link HyperellipticalMap}, which takes parameters so it can fit
 * any shape between a circle or ellipse and a rectangle (the default is a slightly squared-off ellipse). You can access
 * the height map with the {@link #heightData} field, the heat map with the {@link #heatData} field, the moisture map
 * with the {@link #moistureData} field, and a special map that stores ints representing the codes for various ranges of
 * elevation (0 to 8 inclusive, with 0 the deepest ocean and 8 the highest mountains) with {@link #heightCodeData}. The
 * last map should be noted as being the simplest way to find what is land and what is water; any height code 4 or
 * greater is land, and any height code 3 or less is water.
 * <br>
 * Biome mapping is likely to need customization per-game, but some good starting points are {@link SimpleBiomeMapper},
 * which stores one biome per cell, and {@link DetailedBiomeMapper}, which gives each cell a midway value between two
 * biomes.
 */
public abstract class WorldMapGenerator implements Serializable {
    private static final long serialVersionUID = 1L;
    public final int width, height;
    public int seedA, seedB, cacheA, cacheB;
    public SilkRNG rng;
    public final double[][] heightData, heatData, moistureData;
    public final int[][] heightCodeData;
    public double landModifier = -1.0, heatModifier = 1.0,
            minHeight = Double.POSITIVE_INFINITY, maxHeight = Double.NEGATIVE_INFINITY,
            minHeightActual = Double.POSITIVE_INFINITY, maxHeightActual = Double.NEGATIVE_INFINITY,
            minHeat = Double.POSITIVE_INFINITY, maxHeat = Double.NEGATIVE_INFINITY,
            minWet = Double.POSITIVE_INFINITY, maxWet = Double.NEGATIVE_INFINITY;
    protected double centerLongitude = 0.0;

    public int zoom = 0, startX = 0, startY = 0, usedWidth, usedHeight;
    protected IntArray startCacheX = new IntArray(8), startCacheY = new IntArray(8);
    protected int zoomStartX = 0, zoomStartY = 0;
    protected static final double terrainFreq = 1.375, terrainRidgedFreq = 3.1 * 0.5, heatFreq = 2.1, moistureFreq = 2.125, otherFreq = 3.375;

    /**
     * A Noise that has a higher frequency than that class defaults to, which is useful for maps here. With the
     * default Noise frequency of 1f/32f, the maps this produces are giant blurs.
     * <br>
     * Even though this is a Noise and so technically can be edited, that seems to have issues when there's more
     * than one WorldMapGenerator that uses this field. So you can feel free to use this as a Noise or Noise when
     * generators need one, but don't change it too much, if at all.
     */
    public static final Noise DEFAULT_NOISE = new Noise(0x1337CAFE, 1f, Noise.SIMPLEX_FRACTAL, 1);

    /**
     * Used to implement most of the copy constructor for subclasses; this cannot copy Noise implementations and leaves
     * that up to the subclass, but will copy all non-static fields defined in WorldMapGenerator from other.
     * @param other a WorldMapGenerator (subclass) to copy fields from
     */
    protected WorldMapGenerator(WorldMapGenerator other) {
        width = other.width;
        height = other.height;
        usedWidth = other.usedWidth;
        usedHeight = other.usedHeight;
        landModifier = other.landModifier;
        heatModifier = other.heatModifier;
        minHeat = other.minHeat;
        maxHeat = other.maxHeat;
        minHeight = other.minHeight;
        maxHeight = other.maxHeight;
        minHeightActual = other.minHeightActual;
        maxHeightActual = other.maxHeightActual;
        minWet = other.minWet;
        maxWet = other.maxWet;
        centerLongitude = other.centerLongitude;
        zoom = other.zoom;
        startX = other.startX;
        startY = other.startY;
        startCacheX.addAll(other.startCacheX);
        startCacheY.addAll(other.startCacheY);
        zoomStartX = other.zoomStartX;
        zoomStartY = other.zoomStartY;
        seedA = other.seedA;
        seedB = other.seedB;
        cacheA = other.cacheA;
        cacheB = other.cacheB;
        rng = other.rng.copy();
        heightData = copy(other.heightData);
        heatData = copy(other.heatData);
        moistureData = copy(other.moistureData);
        heightCodeData = copy(other.heightCodeData);
    }

    /**
     * Gets the longitude line the map is centered on, which should usually be between 0 and 2 * PI.
     * @return the longitude line the map is centered on, in radians from 0 to 2 * PI
     */
    public double getCenterLongitude() {
        return centerLongitude;
    }

    /**
     * Sets the center longitude line to a longitude measured in radians, from 0 to 2 * PI. Positive arguments will be
     * corrected with modulo, but negative ones may not always act as expected, and are strongly discouraged.
     * @param centerLongitude the longitude to center the map projection on, from 0 to 2 * PI (can be any non-negative double).
     */
    public void setCenterLongitude(double centerLongitude) {
        this.centerLongitude = centerLongitude % 6.283185307179586;
    }

    public static final double
            deepWaterLower = -1.0, deepWaterUpper = -0.7,        // 0
            mediumWaterLower = -0.7, mediumWaterUpper = -0.3,    // 1
            shallowWaterLower = -0.3, shallowWaterUpper = -0.1,  // 2
            coastalWaterLower = -0.1, coastalWaterUpper = 0.02,   // 3
            sandLower = 0.02, sandUpper = 0.12,                   // 4
            grassLower = 0.14, grassUpper = 0.35,                // 5
            forestLower = 0.35, forestUpper = 0.6,               // 6
            rockLower = 0.6, rockUpper = 0.8,                    // 7
            snowLower = 0.8, snowUpper = 1.0;                    // 8

    protected static double removeExcess(double radians)
    {
        radians *= 0.6366197723675814;
        final int floor = (radians >= 0.0 ? (int) radians : (int) radians - 1);
        return (radians - (floor & -2) - ((floor & 1) << 1)) * (Math.PI);
//        if(radians < -Math.PI || radians > Math.PI)
//            System.out.println("UH OH, radians produced: " + radians);
//        if(Math.random() < 0.00001)
//            System.out.println(radians);
//        return radians;

    }
    /**
     * Constructs a WorldMapGenerator (this class is abstract, so you should typically call this from a subclass or as
     * part of an anonymous class that implements {@link #regenerate(int, int, int, int, double, double, int, int)}).
     * Always makes a 256x256 map. If you were using {@link WorldMapGenerator#WorldMapGenerator(long, int, int)}, then
     * this would be the same as passing the parameters {@code 0x1337BABE1337D00DL, 256, 256}.
     */
    protected WorldMapGenerator()
    {
        this(0x1337BABE1337D00DL, 256, 256);
    }
    /**
     * Constructs a WorldMapGenerator (this class is abstract, so you should typically call this from a subclass or as
     * part of an anonymous class that implements {@link #regenerate(int, int, int, int, double, double, int, int)}).
     * Takes only the width/height of the map. The initial seed is set to the same large long
     * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
     * height of the map cannot be changed after the fact, but you can zoom in.
     *
     * @param mapWidth the width of the map(s) to generate; cannot be changed later
     * @param mapHeight the height of the map(s) to generate; cannot be changed later
     */
    protected WorldMapGenerator(int mapWidth, int mapHeight)
    {
        this(0x1337BABE1337D00DL, mapWidth, mapHeight);
    }
    /**
     * Constructs a WorldMapGenerator (this class is abstract, so you should typically call this from a subclass or as
     * part of an anonymous class that implements {@link #regenerate(int, int, int, int, double, double, int, int)}).
     * Takes an initial seed and the width/height of the map. The {@code initialSeed}
     * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
     * The width and height of the map cannot be changed after the fact, but you can zoom in.
     *
     * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
     * @param mapWidth the width of the map(s) to generate; cannot be changed later
     * @param mapHeight the height of the map(s) to generate; cannot be changed later
     */
    protected WorldMapGenerator(long initialSeed, int mapWidth, int mapHeight)
    {
        width = mapWidth;
        height = mapHeight;
        usedWidth = width;
        usedHeight = height;
        seedA = (int) (initialSeed & 0xFFFFFFFFL);
        seedB = (int) (initialSeed >>> 32);
        cacheA = ~seedA;
        cacheB = ~seedB;
        rng = new SilkRNG(seedA, seedB);
        heightData = new double[width][height];
        heatData = new double[width][height];
        moistureData = new double[width][height];
        heightCodeData = new int[width][height];
    }

    /**
     * Generates a world using a random RNG state and all parameters randomized.
     * The worlds this produces will always have width and height as specified in the constructor (default 256x256).
     * You can call {@link #zoomIn(int, int, int)} to double the resolution and center on the specified area, but the width
     * and height of the 2D arrays this changed, such as {@link #heightData} and {@link #moistureData} will be the same.
     */
    public void generate()
    {
        generate(rng.nextLong());
    }

    /**
     * Generates a world using the specified RNG state as a long. Other parameters will be randomized, using the same
     * RNG state to start with.
     * The worlds this produces will always have width and height as specified in the constructor (default 256x256).
     * You can call {@link #zoomIn(int, int, int)} to double the resolution and center on the specified area, but the width
     * and height of the 2D arrays this changed, such as {@link #heightData} and {@link #moistureData} will be the same.
     * @param state the state to give this generator's RNG; if the same as the last call, this will reuse data
     */
    public void generate(long state) {
        generate(-1.0, -1.0, state);
    }

    /**
     * Generates a world using the specified RNG state as a long, with specific land and heat modifiers that affect
     * the land-water ratio and the average temperature, respectively.
     * The worlds this produces will always have width and height as specified in the constructor (default 256x256).
     * You can call {@link #zoomIn(int, int, int)} to double the resolution and center on the specified area, but the width
     * and height of the 2D arrays this changed, such as {@link #heightData} and {@link #moistureData} will be the same.
     * @param landMod 1.0 is Earth-like, less than 1 is more-water, more than 1 is more-land; a random value will be used if this is negative
     * @param heatMod 1.125 is Earth-like, less than 1 is cooler, more than 1 is hotter; a random value will be used if this is negative
     * @param state the state to give this generator's RNG; if the same as the last call, this will reuse data
     */
    public void generate(double landMod, double heatMod, long state)
    {
        if(cacheA != (int) (state & 0xFFFFFFFFL) || cacheB != (int) (state >>> 32) ||
                landMod != landModifier || heatMod != heatModifier)
        {
            seedA = (int) (state & 0xFFFFFFFFL);
            seedB = (int) (state >>> 32);
            zoom = 0;
            startCacheX.clear();
            startCacheY.clear();
            startCacheX.add(0);
            startCacheY.add(0);
            zoomStartX = width >> 1;
            zoomStartY = height >> 1;

        }
        //System.out.printf("generate, zoomStartX: %d, zoomStartY: %d\n", zoomStartX, zoomStartY);

        regenerate(startX = (zoomStartX >> zoom) - (width >> 1 + zoom), startY = (zoomStartY >> zoom) - (height >> 1 + zoom),
                //startCacheX.peek(), startCacheY.peek(),
                usedWidth = (width >> zoom), usedHeight = (height >> zoom), landMod, heatMod, seedA, seedB);
    }

    /**
     * Halves the resolution of the map and doubles the area it covers; the 2D arrays this uses keep their sizes. This
     * version of zoomOut always zooms out from the center of the currently used area.
     * <br>
     * Only has an effect if you have previously zoomed in using {@link #zoomIn(int, int, int)} or its overload.
     */
    public void zoomOut()
    {
        zoomOut(1, width >> 1, height >> 1);
    }
    /**
     * Halves the resolution of the map and doubles the area it covers repeatedly, halving {@code zoomAmount} times; the
     * 2D arrays this uses keep their sizes. This version of zoomOut allows you to specify where the zoom should be
     * centered, using the current coordinates (if the map size is 256x256, then coordinates should be between 0 and
     * 255, and will refer to the currently used area and not necessarily the full world size).
     * <br>
     * Only has an effect if you have previously zoomed in using {@link #zoomIn(int, int, int)} or its overload.
     * @param zoomCenterX the center X position to zoom out from; if too close to an edge, this will stop moving before it would extend past an edge
     * @param zoomCenterY the center Y position to zoom out from; if too close to an edge, this will stop moving before it would extend past an edge
     */
    public void zoomOut(int zoomAmount, int zoomCenterX, int zoomCenterY)
    {
        zoomAmount = Math.min(zoom, zoomAmount);
        if(zoomAmount == 0) return;
        if(zoomAmount < 0) {
            zoomIn(-zoomAmount, zoomCenterX, zoomCenterY);
            return;
        }
        if(zoom > 0)
        {
            if(cacheA != seedA || cacheB != seedB)
            {
                generate(rng.nextLong());
            }
            zoomStartX = Math.min(Math.max(
                    (zoomStartX + (zoomCenterX - (width >> 1))) >> zoomAmount,
                    width >> 1), (width << zoom - zoomAmount) - (width >> 1));
            zoomStartY = Math.min(Math.max(
                    (zoomStartY + (zoomCenterY - (height >> 1))) >> zoomAmount,
                    height >> 1), (height << zoom - zoomAmount) - (height >> 1));
//            System.out.printf("zoomOut, zoomStartX: %d, zoomStartY: %d\n", zoomStartX, zoomStartY);
            zoom -= zoomAmount;
            startCacheX.pop();
            startCacheY.pop();
            startCacheX.add(Math.min(Math.max(startCacheX.pop() + (zoomCenterX >> zoom + 1) - (width >> zoom + 2),
                    0), width - (width >> zoom)));
            startCacheY.add(Math.min(Math.max(startCacheY.pop() + (zoomCenterY >> zoom + 1) - (height >> zoom + 2),
                    0), height - (height >> zoom)));
//            zoomStartX = Math.min(Math.max((zoomStartX >> 1) + (zoomCenterX >> zoom + 1) - (width >> zoom + 2),
//                    0), width - (width >> zoom));
//            zoomStartY = Math.min(Math.max((zoomStartY >> 1) + (zoomCenterY >> zoom + 1) - (height >> zoom + 2),
//                    0), height - (height >> zoom));
            regenerate(startX = (zoomStartX >> zoom) - (width >> zoom + 1), startY = (zoomStartY >> zoom) - (height >> zoom + 1),
                    //startCacheX.peek(), startCacheY.peek(),
                    usedWidth = width >> zoom,  usedHeight = height >> zoom,
                    landModifier, heatModifier, cacheA, cacheB);
            rng.setState(cacheA, cacheB);
        }

    }
    /**
     * Doubles the resolution of the map and halves the area it covers; the 2D arrays this uses keep their sizes. This
     * version of zoomIn always zooms in to the center of the currently used area.
     * <br>
     * Although there is no technical restriction on maximum zoom, zooming in more than 5 times (64x scale or greater)
     * will make the map appear somewhat less realistic due to rounded shapes appearing more bubble-like and less like a
     * normal landscape.
     */
    public void zoomIn()
    {
        zoomIn(1, width >> 1, height >> 1);
    }
    /**
     * Doubles the resolution of the map and halves the area it covers repeatedly, doubling {@code zoomAmount} times;
     * the 2D arrays this uses keep their sizes. This version of zoomIn allows you to specify where the zoom should be
     * centered, using the current coordinates (if the map size is 256x256, then coordinates should be between 0 and
     * 255, and will refer to the currently used area and not necessarily the full world size).
     * <br>
     * Although there is no technical restriction on maximum zoom, zooming in more than 5 times (64x scale or greater)
     * will make the map appear somewhat less realistic due to rounded shapes appearing more bubble-like and less like a
     * normal landscape.
     * @param zoomCenterX the center X position to zoom in to; if too close to an edge, this will stop moving before it would extend past an edge
     * @param zoomCenterY the center Y position to zoom in to; if too close to an edge, this will stop moving before it would extend past an edge
     */
    public void zoomIn(int zoomAmount, int zoomCenterX, int zoomCenterY)
    {
        if(zoomAmount == 0) return;
        if(zoomAmount < 0)
        {
            zoomOut(-zoomAmount, zoomCenterX, zoomCenterY);
            return;
        }
        if(seedA != cacheA || seedB != cacheB)
        {
            generate(rng.nextLong());
        }
        zoomStartX = Math.min(Math.max(
                (zoomStartX + zoomCenterX - (width >> 1) << zoomAmount),
                width >> 1), (width << zoom + zoomAmount) - (width >> 1));
//        int oldZoomY = zoomStartY;
        zoomStartY = Math.min(Math.max(
                (zoomStartY + zoomCenterY - (height >> 1) << zoomAmount),
                height >> 1), (height << zoom + zoomAmount) - (height >> 1));
//        System.out.printf("zoomIn, zoomStartX: %d, zoomStartY: %d, oldZoomY: %d, unedited: %d, upperCap: %d\n", zoomStartX, zoomStartY,
//                oldZoomY, (oldZoomY + zoomCenterY - (height >> 1) << zoomAmount), (height << zoom + zoomAmount) - (height >> 1));
        zoom += zoomAmount;
        if(startCacheX.isEmpty())
        {
            startCacheX.add(0);
            startCacheY.add(0);
        }
        else {
            startCacheX.add(Math.min(Math.max(startCacheX.peek() + (zoomCenterX >> zoom - 1) - (width >> zoom + 1),
                    0), width - (width >> zoom)));
            startCacheY.add(Math.min(Math.max(startCacheY.peek() + (zoomCenterY >> zoom - 1) - (height >> zoom + 1),
                    0), height - (height >> zoom)));
        }
        regenerate(startX = (zoomStartX >> zoom) - (width >> 1 + zoom), startY = (zoomStartY >> zoom) - (height >> 1 + zoom),
                //startCacheX.peek(), startCacheY.peek(),
                usedWidth = width >> zoom, usedHeight = height >> zoom,
                landModifier, heatModifier, cacheA, cacheB);
        rng.setState(cacheA, cacheB);
    }

    protected abstract void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                       double landMod, double heatMod, int stateA, int stateB);

    public int codeHeight(final double high)
    {
        if(high < deepWaterUpper)
            return 0;
        if(high < mediumWaterUpper)
            return 1;
        if(high < shallowWaterUpper)
            return 2;
        if(high < coastalWaterUpper)
            return 3;
        if(high < sandUpper)
            return 4;
        if(high < grassUpper)
            return 5;
        if(high < forestUpper)
            return 6;
        if(high < rockUpper)
            return 7;
        return 8;
    }
    protected final int decodeX(final int coded)
    {
        return coded % width;
    }
    protected final int decodeY(final int coded)
    {
        return coded / width;
    }
    public int wrapX(final int x, final int y)  {
        return (x + width) % width;
    }
    public int wrapY(final int x, final int y)  {
        return (y + height) % height;
    }
    
//    private static final Direction[] reuse = new Direction[6];
//    private void appendDirToShuffle(RNG rng) {
//        rng.randomPortion(Direction.CARDINALS, reuse);
//        reuse[rng.next(2)] = Direction.DIAGONALS[rng.next(2)];
//        reuse[4] = Direction.DIAGONALS[rng.next(2)];
//        reuse[5] = Direction.OUTWARDS[rng.next(3)];
//    }

//    protected void addRivers()
//    {
//        
//        long rebuildState = rng.nextLong();
//        //workingData.allOn();
//                //.empty().insertRectangle(8, 8, width - 16, height - 16);
//        riverData.empty().refill(heightCodeData, 6, 100);
//        riverData.quasiRandomRegion(0.0036);
//        int[] starts = riverData.asTightEncoded();
//        int len = starts.length, currentPos, choice, adjX, adjY, currX, currY, tcx, tcy, stx, sty, sbx, sby;
//        riverData.clear();
//        lakeData.clear();
//        PER_RIVER:
//        for (int i = 0; i < len; i++) {
//            workingData.clear();
//            currentPos = starts[i];
//            stx = tcx = currX = decodeX(currentPos);
//            sty = tcy = currY = decodeY(currentPos);
//            while (true) {
//
//                double best = 999999;
//                choice = -1;
//                appendDirToShuffle(rng);
//
//                for (int d = 0; d < 5; d++) {
//                    adjX = wrapX(currX + reuse[d].deltaX);
//                    /*
//                    if (adjX < 0 || adjX >= width)
//                    {
//                        if(rng.next(4) == 0)
//                            riverData.or(workingData);
//                        continue PER_RIVER;
//                    }*/
//                    adjY = wrapY(currY + reuse[d].deltaY);
//                    if (heightData[adjX][adjY] < best && !workingData.contains(adjX, adjY)) {
//                        best = heightData[adjX][adjY];
//                        choice = d;
//                        tcx = adjX;
//                        tcy = adjY;
//                    }
//                }
//                currX = tcx;
//                currY = tcy;
//                if (best >= heightData[stx][sty]) {
//                    tcx = rng.next(2);
//                    adjX = wrapX(currX + ((tcx & 1) << 1) - 1);
//                    adjY = wrapY(currY + (tcx & 2) - 1);
//                    lakeData.insert(currX, currY);
//                    lakeData.insert(wrapX(currX+1), currY);
//                    lakeData.insert(wrapX(currX-1), currY);
//                    lakeData.insert(currX, wrapY(currY+1));
//                    lakeData.insert(currX, wrapY(currY-1));
//
//                    if(heightCodeData[adjX][adjY] <= 3) {
//                        riverData.or(workingData);
//                        continue PER_RIVER;
//                    }
//                    else if((heightData[adjX][adjY] -= 0.0002) < 0.0) {
//                        if (rng.next(3) == 0)
//                            riverData.or(workingData);
//                        continue PER_RIVER;
//                    }
//                    tcx = rng.next(2);
//                    adjX = wrapX(currX + ((tcx & 1) << 1) - 1);
//                    adjY = wrapY(currY + (tcx & 2) - 1);
//                    if(heightCodeData[adjX][adjY] <= 3) {
//                        riverData.or(workingData);
//                        continue PER_RIVER;
//                    }
//                    else if((heightData[adjX][adjY] -= 0.0002) < 0.0) {
//                        if (rng.next(3) == 0)
//                            riverData.or(workingData);
//                        continue PER_RIVER;
//                    }
//                }
//                if(choice != -1 && reuse[choice].isDiagonal())
//                {
//                    tcx = wrapX(currX - reuse[choice].deltaX);
//                    tcy = wrapY(currY - reuse[choice].deltaY);
//                    if(heightData[tcx][currY] <= heightData[currX][tcy] && !workingData.contains(tcx, currY))
//                    {
//                        if(heightCodeData[tcx][currY] < 3 || riverData.contains(tcx, currY))
//                        {
//                            riverData.or(workingData);
//                            continue PER_RIVER;
//                        }
//                        workingData.insert(tcx, currY);
//                    }
//                    else if(!workingData.contains(currX, tcy))
//                    {
//                        if(heightCodeData[currX][tcy] < 3 || riverData.contains(currX, tcy))
//                        {
//                            riverData.or(workingData);
//                            continue PER_RIVER;
//                        }
//                        workingData.insert(currX, tcy);
//
//                    }
//                }
//                if(heightCodeData[currX][currY] < 3 || riverData.contains(currX, currY))
//                {
//                    riverData.or(workingData);
//                    continue PER_RIVER;
//                }
//                workingData.insert(currX, currY);
//            }
//        }
//
//        GreasedRegion tempData = new GreasedRegion(width, height);
//        int riverCount = riverData.size() >> 4, currentMax = riverCount >> 3, idx = 0, prevChoice;
//        for (int h = 5; h < 9; h++) { //, currentMax += riverCount / 18
//            workingData.empty().refill(heightCodeData, h).and(riverData);
//            RIVER:
//            for (int j = 0; j < currentMax && idx < riverCount; j++) {
//                double vdc = VanDerCorputQRNG.weakDetermine(idx++), best = -999999;
//                currentPos = workingData.atFractionTight(vdc);
//                if(currentPos < 0)
//                    break;
//                stx = sbx = tcx = currX = decodeX(currentPos);
//                sty = sby = tcy = currY = decodeY(currentPos);
//                appendDirToShuffle(rng);
//                choice = -1;
//                prevChoice = -1;
//                for (int d = 0; d < 5; d++) {
//                    adjX = wrapX(currX + reuse[d].deltaX);
//                    adjY = wrapY(currY + reuse[d].deltaY);
//                    if (heightData[adjX][adjY] > best) {
//                        best = heightData[adjX][adjY];
//                        prevChoice = choice;
//                        choice = d;
//                        sbx = tcx;
//                        sby = tcy;
//                        tcx = adjX;
//                        tcy = adjY;
//                    }
//                }
//                currX = sbx;
//                currY = sby;
//                if (prevChoice != -1 && heightCodeData[currX][currY] >= 4) {
//                    if (reuse[prevChoice].isDiagonal()) {
//                        tcx = wrapX(currX - reuse[prevChoice].deltaX);
//                        tcy = wrapY(currY - reuse[prevChoice].deltaY);
//                        if (heightData[tcx][currY] <= heightData[currX][tcy]) {
//                            if(heightCodeData[tcx][currY] < 3)
//                            {
//                                riverData.or(tempData);
//                                continue;
//                            }
//                            tempData.insert(tcx, currY);
//                        }
//                        else
//                        {
//                            if(heightCodeData[currX][tcy] < 3)
//                            {
//                                riverData.or(tempData);
//                                continue;
//                            }
//                            tempData.insert(currX, tcy);
//                        }
//                    }
//                    if(heightCodeData[currX][currY] < 3)
//                    {
//                        riverData.or(tempData);
//                        continue;
//                    }
//                    tempData.insert(currX, currY);
//                }
//
//                while (true) {
//                    best = -999999;
//                    appendDirToShuffle(rng);
//                    choice = -1;
//                    for (int d = 0; d < 6; d++) {
//                        adjX = wrapX(currX + reuse[d].deltaX);
//                        adjY = wrapY(currY + reuse[d].deltaY);
//                        if (heightData[adjX][adjY] > best && !riverData.contains(adjX, adjY)) {
//                            best = heightData[adjX][adjY];
//                            choice = d;
//                            sbx = adjX;
//                            sby = adjY;
//                        }
//                    }
//                    currX = sbx;
//                    currY = sby;
//                    if (choice != -1) {
//                        if (reuse[choice].isDiagonal()) {
//                            tcx = wrapX(currX - reuse[choice].deltaX);
//                            tcy = wrapY(currY - reuse[choice].deltaY);
//                            if (heightData[tcx][currY] <= heightData[currX][tcy]) {
//                                if(heightCodeData[tcx][currY] < 3)
//                                {
//                                    riverData.or(tempData);
//                                    continue RIVER;
//                                }
//                                tempData.insert(tcx, currY);
//                            }
//                            else
//                            {
//                                if(heightCodeData[currX][tcy] < 3)
//                                {
//                                    riverData.or(tempData);
//                                    continue RIVER;
//                                }
//                                tempData.insert(currX, tcy);
//                            }
//                        }
//                        if(heightCodeData[currX][currY] < 3)
//                        {
//                            riverData.or(tempData);
//                            continue RIVER;
//                        }
//                        tempData.insert(currX, currY);
//                    }
//                    else
//                    {
//                        riverData.or(tempData);
//                        tempData.clear();
//                        continue RIVER;
//                    }
//                    if (best <= heightData[stx][sty] || heightData[currX][currY] > rng.nextDouble(280.0)) {
//                        riverData.or(tempData);
//                        tempData.clear();
//                        if(heightCodeData[currX][currY] < 3)
//                            continue RIVER;
//                        lakeData.insert(currX, currY);
//                        sbx = rng.next(8);
//                        sbx &= sbx >>> 4;
//                        if ((sbx & 1) == 0)
//                            lakeData.insert(wrapX(currX + 1), currY);
//                        if ((sbx & 2) == 0)
//                            lakeData.insert(wrapX(currX - 1), currY);
//                        if ((sbx & 4) == 0)
//                            lakeData.insert(currX, wrapY(currY + 1));
//                        if ((sbx & 8) == 0)
//                            lakeData.insert(currX, wrapY(currY - 1));
//                        sbx = rng.next(2);
//                        lakeData.insert(wrapX(currX + (-(sbx & 1) | 1)), wrapY(currY + ((sbx & 2) - 1))); // random diagonal
//                        lakeData.insert(currX, wrapY(currY + ((sbx & 2) - 1))); // ortho next to random diagonal
//                        lakeData.insert(wrapX(currX + (-(sbx & 1) | 1)), currY); // ortho next to random diagonal
//
//                        continue RIVER;
//                    }
//                }
//            }
//
//        }
//
//        rng.setState(rebuildState);
//    }

    public interface BiomeMapper
    {
        /**
         * Gets the most relevant biome code for a given x,y point on the map. Some mappers can store more than one
         * biome at a location, but only the one with the highest influence will be returned by this method. Biome codes
         * are always ints, and are typically between 0 and 60, both inclusive; they are meant to be used as indices
         * into a table of names or other objects that identify a biome, accessible via {@link #getBiomeNameTable()}.
         * Although different classes may define biome codes differently, they should all be able to be used as indices
         * into the String array returned by getBiomeNameTable().
         * @param x the x-coordinate on the map
         * @param y the y-coordinate on the map
         * @return an int that can be used as an index into the array returned by {@link #getBiomeNameTable()}
         */
        int getBiomeCode(int x, int y);

        /**
         * Gets a heat code for a given x,y point on a map, usually as an int between 0 and 5 inclusive. Some
         * implementations may use more or less detail for heat codes, but 0 is always the coldest code used, and the
         * highest value this can return for a given implementation refers to the hottest code used.
         * @param x the x-coordinate on the map
         * @param y the y-coordinate on the map
         * @return an int that can be used to categorize how hot an area is, with 0 as coldest
         */
        int getHeatCode(int x, int y);
        /**
         * Gets a moisture code for a given x,y point on a map, usually as an int between 0 and 5 inclusive. Some
         * implementations may use more or less detail for moisture codes, but 0 is always the driest code used, and the
         * highest value this can return for a given implementation refers to the wettest code used. Some
         * implementations may allow seasonal change in moisture, e.g. monsoon seasons, to be modeled differently from
         * average precipitation in an area, but the default assumption is that this describes the average amount of
         * moisture (rain, humidity, and possibly snow/hail or other precipitation) that an area receives annually.
         * @param x the x-coordinate on the map
         * @param y the y-coordinate on the map
         * @return an int that can be used to categorize how much moisture an area tends to receive, with 0 as driest
         */
        int getMoistureCode(int x, int y);

        /**
         * Gets a String array where biome codes can be used as indices to look up a name for the biome they refer to. A
         * sample table is in {@link SimpleBiomeMapper#biomeTable}; the 61-element array format documented for that
         * field is encouraged for implementing classes if they use 6 levels of heat and 6 levels of moisture, and track
         * rivers, coastlines, lakes, and oceans as potentially different types of terrain. Biome codes can be obtained
         * with {@link #getBiomeCode(int, int)}, or for some implementing classes other methods may provide more
         * detailed information.
         * @return a String array that often contains 61 elements, to be used with biome codes as indices.
         */
        String[] getBiomeNameTable();
        /**
         * Analyzes the last world produced by the given WorldMapGenerator and uses all of its generated information to
         * assign biome codes for each cell (along with heat and moisture codes). After calling this, biome codes can be
         * retrieved with {@link #getBiomeCode(int, int)} and used as indices into {@link #getBiomeNameTable()} or a
         * custom biome table.
         * @param world a WorldMapGenerator that should have generated at least one map; it may be at any zoom
         */
        void makeBiomes(WorldMapGenerator world);
    }
    /**
     * A way to get biome information for the cells on a map when you only need a single value to describe a biome, such
     * as "Grassland" or "TropicalRainforest".
     * <br>
     * To use: 1, Construct a SimpleBiomeMapper (constructor takes no arguments). 2, call
     * {@link #makeBiomes(WorldMapGenerator)} with a WorldMapGenerator that has already produced at least one world map.
     * 3, get biome codes from the {@link #biomeCodeData} field, where a code is an int that can be used as an index
     * into the {@link #biomeTable} static field to get a String name for a biome type, or used with an alternate biome
     * table of your design. Biome tables in this case are 61-element arrays organized into groups of 6 elements, with
     * the last element reserved for empty space where the map doesn't cover (as with some map projections). Each
     * group goes from the coldest temperature first to the warmest temperature last in the group. The first group of 6
     * contains the dryest biomes, the next 6 are medium-dry, the next are slightly-dry, the next slightly-wet, then
     * medium-wet, then wettest. After this first block of dry-to-wet groups, there is a group of 6 for coastlines, a
     * group of 6 for rivers, a group of 6 for lakes, a group of 6 for oceans, and then one element for space outside
     * the map. The last element, with code 60, is by convention the String "Empty", but normally the code should be
     * enough to tell that a space is off-map. This also assigns moisture codes and heat codes from 0 to 5 for each
     * cell, which may be useful to simplify logic that deals with those factors.
     */
    public static class SimpleBiomeMapper implements BiomeMapper
    {
        /**
         * The heat codes for the analyzed map, from 0 to 5 inclusive, with 0 coldest and 5 hottest.
         */
        public int[][] heatCodeData,
        /**
         * The moisture codes for the analyzed map, from 0 to 5 inclusive, with 0 driest and 5 wettest.
         */
        moistureCodeData,
        /**
         * The biome codes for the analyzed map, from 0 to 60 inclusive. You can use {@link #biomeTable} to look up
         * String names for biomes, or construct your own table as you see fit (see docs in {@link SimpleBiomeMapper}).
         */
        biomeCodeData;

        @Override
        public int getBiomeCode(int x, int y) {
            return biomeCodeData[x][y];
        }

        @Override
        public int getHeatCode(int x, int y) {
            return heatCodeData[x][y];
        }

        @Override
        public int getMoistureCode(int x, int y) {
            return moistureCodeData[x][y];
        }

        /**
         * Gets a String array where biome codes can be used as indices to look up a name for the biome they refer to.
         * This table uses 6 levels of heat and 6 levels of moisture, and tracks rivers, coastlines, lakes, and oceans
         * as potentially different types of terrain. Biome codes can be obtained with {@link #getBiomeCode(int, int)}.
         * This method returns a direct reference to {@link #biomeTable}, so modifying the returned array is discouraged
         * (you should implement {@link BiomeMapper} using this class as a basis if you want to change its size).
         * @return a direct reference to {@link #biomeTable}, a String array containing names of biomes
         */
        @Override
        public String[] getBiomeNameTable() {
            return biomeTable;
        }

        public static final double
                coldestValueLower = 0.0,   coldestValueUpper = 0.15, // 0
                colderValueLower = 0.15,   colderValueUpper = 0.31,  // 1
                coldValueLower = 0.31,     coldValueUpper = 0.5,     // 2
                warmValueLower = 0.5,      warmValueUpper = 0.69,    // 3
                warmerValueLower = 0.69,   warmerValueUpper = 0.85,  // 4
                warmestValueLower = 0.85,  warmestValueUpper = 1.0,  // 5

        driestValueLower = 0.0,    driestValueUpper  = 0.27, // 0
                drierValueLower = 0.27,    drierValueUpper   = 0.4,  // 1
                dryValueLower = 0.4,       dryValueUpper     = 0.6,  // 2
                wetValueLower = 0.6,       wetValueUpper     = 0.8,  // 3
                wetterValueLower = 0.8,    wetterValueUpper  = 0.9,  // 4
                wettestValueLower = 0.9,   wettestValueUpper = 1.0;  // 5

        /**
         * The default biome table to use with biome codes from {@link #biomeCodeData}. Biomes are assigned based on
         * heat and moisture for the first 36 of 61 elements (coldest to warmest for each group of 6, with the first
         * group as the dryest and the last group the wettest), then the next 6 are for coastlines (coldest to warmest),
         * then rivers (coldest to warmest), then lakes (coldest to warmest), then oceans (coldest to warmest), and
         * lastly a single "biome" for empty space outside the map (meant for projections that don't fill a rectangle).
         */
        public static final String[] biomeTable = {
                //COLDEST //COLDER        //COLD            //HOT                  //HOTTER              //HOTTEST
                "Ice",    "Ice",          "Grassland",      "Desert",              "Desert",             "Desert",             //DRYEST
                "Ice",    "Tundra",       "Grassland",      "Grassland",           "Desert",             "Desert",             //DRYER
                "Ice",    "Tundra",       "Woodland",       "Woodland",            "Savanna",            "Desert",             //DRY
                "Ice",    "Tundra",       "SeasonalForest", "SeasonalForest",      "Savanna",            "Savanna",            //WET
                "Ice",    "Tundra",       "BorealForest",   "TemperateRainforest", "TropicalRainforest", "Savanna",            //WETTER
                "Ice",    "BorealForest", "BorealForest",   "TemperateRainforest", "TropicalRainforest", "TropicalRainforest", //WETTEST
                "Rocky",  "Rocky",        "Beach",          "Beach",               "Beach",              "Beach",              //COASTS
                "Ice",    "River",        "River",          "River",               "River",              "River",              //RIVERS
                "Ice",    "River",        "River",          "River",               "River",              "River",              //LAKES
                "Ocean",  "Ocean",        "Ocean",          "Ocean",               "Ocean",              "Ocean",              //OCEAN
                "Empty",                                                                                                       //SPACE
        };

        /**
         * Simple constructor; pretty much does nothing. Make sure to call {@link #makeBiomes(WorldMapGenerator)} before
         * using fields like {@link #biomeCodeData}.
         */
        public SimpleBiomeMapper()
        {
            heatCodeData = null;
            moistureCodeData = null;
            biomeCodeData = null;
        }

        /**
         * Analyzes the last world produced by the given WorldMapGenerator and uses all of its generated information to
         * assign biome codes for each cell (along with heat and moisture codes). After calling this, biome codes can be
         * taken from {@link #biomeCodeData} and used as indices into {@link #biomeTable} or a custom biome table.
         * @param world a WorldMapGenerator that should have generated at least one map; it may be at any zoom
         */
        @Override
        public void makeBiomes(WorldMapGenerator world) {
            if(world == null || world.width <= 0 || world.height <= 0)
                return;
            if(heatCodeData == null || (heatCodeData.length != world.width || heatCodeData[0].length != world.height))
                heatCodeData = new int[world.width][world.height];
            if(moistureCodeData == null || (moistureCodeData.length != world.width || moistureCodeData[0].length != world.height))
                moistureCodeData = new int[world.width][world.height];
            if(biomeCodeData == null || (biomeCodeData.length != world.width || biomeCodeData[0].length != world.height))
                biomeCodeData = new int[world.width][world.height];
            final double i_hot = (world.maxHeat == world.minHeat) ? 1.0 : 1.0 / (world.maxHeat - world.minHeat);
            for (int x = 0; x < world.width; x++) {
                for (int y = 0; y < world.height; y++) {
                    final double hot = (world.heatData[x][y] - world.minHeat) * i_hot, moist = world.moistureData[x][y];
                    final int heightCode = world.heightCodeData[x][y];
                    if(heightCode == 1000) {
                        biomeCodeData[x][y] = 60;
                        continue;
                    }
                    int hc, mc;
                    boolean isLake = false,// world.generateRivers && heightCode >= 4 && fresh > 0.65 && fresh + moist * 2.35 > 2.75,//world.partialLakeData.contains(x, y) && heightCode >= 4,
                            isRiver = false;// world.generateRivers && !isLake && heightCode >= 4 && fresh > 0.55 && fresh + moist * 2.2 > 2.15;//world.partialRiverData.contains(x, y) && heightCode >= 4;
                    if(heightCode < 4) {
                        mc = 9;
                    }
                    else if (moist > wetterValueUpper) {
                        mc = 5;
                    } else if (moist > wetValueUpper) {
                        mc = 4;
                    } else if (moist > dryValueUpper) {
                        mc = 3;
                    } else if (moist > drierValueUpper) {
                        mc = 2;
                    } else if (moist > driestValueUpper) {
                        mc = 1;
                    } else {
                        mc = 0;
                    }

                    if (hot > warmerValueUpper) {
                        hc = 5;
                    } else if (hot > warmValueUpper) {
                        hc = 4;
                    } else if (hot > coldValueUpper) {
                        hc = 3;
                    } else if (hot > colderValueUpper) {
                        hc = 2;
                    } else if (hot > coldestValueUpper) {
                        hc = 1;
                    } else {
                        hc = 0;
                    }

                    heatCodeData[x][y] = hc;
                    moistureCodeData[x][y] = mc;
                    biomeCodeData[x][y] = heightCode < 4 ? hc + 54 // 54 == 9 * 6, 9 is used for Ocean groups
                            : isLake ? hc + 48 : (isRiver ? hc + 42 : ((heightCode == 4) ? hc + 36 : hc + mc * 6));
                }
            }
        }
    }
    /**
     * A way to get biome information for the cells on a map when you want an area's biome to be a combination of two
     * main biome types, such as "Grassland" or "TropicalRainforest", with the biomes varying in weight between areas.
     * <br>
     * To use: 1, Construct a DetailedBiomeMapper (constructor takes no arguments). 2, call
     * {@link #makeBiomes(WorldMapGenerator)} with a WorldMapGenerator that has already produced at least one world map.
     * 3, get biome codes from the {@link #biomeCodeData} field, where a code is an int that can be used with the
     * extract methods in this class to get various information from it (these are {@link #extractBiomeA(int)},
     * {@link #extractBiomeB(int)}, {@link #extractPartA(int)}, {@link #extractPartB(int)}, and
     * {@link #extractMixAmount(int)}). You can get predefined names for biomes using the extractBiome methods (these
     * names can be changed in {@link #biomeTable}), or raw indices into some (usually 61-element) collection or array
     * with the extractPart methods. The extractMixAmount() method gets a float that is the amount by which biome B
     * affects biome A; if this is higher than 0.5, then biome B is the "dominant" biome in the area.
     */
    public static class DetailedBiomeMapper implements BiomeMapper
    {
        /**
         * The heat codes for the analyzed map, from 0 to 5 inclusive, with 0 coldest and 5 hottest.
         */
        public int[][] heatCodeData,
        /**
         * The moisture codes for the analyzed map, from 0 to 5 inclusive, with 0 driest and 5 wettest.
         */
        moistureCodeData,
        /**
         * The biome codes for the analyzed map, using one int to store the codes for two biomes and the degree by which
         * the second biome affects the first. These codes can be used with methods in this class like
         * {@link #extractBiomeA(int)}, {@link #extractBiomeB(int)}, and {@link #extractMixAmount(int)} to find the two
         * dominant biomes in an area, called biome A and biome B, and the mix amount, for finding how much biome B
         * affects biome A.
         */
        biomeCodeData;


        /**
         * Gets the biome code for the dominant biome at a given x,y position. This is equivalent to getting the raw
         * biome code from {@link #biomeCodeData}, calling {@link #extractMixAmount(int)} on that raw biome code, and
         * chooosing whether to call {@link #extractPartA(int)} or {@link #extractPartB(int)} based on whether the mix
         * amount is lower than 0.5 (yielding part A) or higher (yielding part B).
         * @param x the x-coordinate on the map
         * @param y the y-coordinate on the map
         * @return the biome code for the dominant biome part at the given location
         */
        @Override
        public int getBiomeCode(int x, int y) {
            int code = biomeCodeData[x][y];
            if(code < 0x2000000) return code & 1023;
            return (code >>> 10) & 1023;
        }

        @Override
        public int getHeatCode(int x, int y) {
            return heatCodeData[x][y];
        }

        @Override
        public int getMoistureCode(int x, int y) {
            return moistureCodeData[x][y];
        }

        /**
         * Gets a String array where biome codes can be used as indices to look up a name for the biome they refer to.
         * This table uses 6 levels of heat and 6 levels of moisture, and tracks rivers, coastlines, lakes, and oceans
         * as potentially different types of terrain. Biome codes can be obtained with {@link #getBiomeCode(int, int)}.
         * This method returns a direct reference to {@link #biomeTable}, so modifying the returned array is discouraged
         * (you should implement {@link BiomeMapper} using this class as a basis if you want to change its size).
         * @return a direct reference to {@link #biomeTable}, a String array containing names of biomes
         */
        @Override
        public String[] getBiomeNameTable() {
            return biomeTable;
        }

        public static final double
                coldestValueLower = 0.0,   coldestValueUpper = 0.15, // 0
                colderValueLower = 0.15,   colderValueUpper = 0.31,  // 1
                coldValueLower = 0.31,     coldValueUpper = 0.5,     // 2
                warmValueLower = 0.5,      warmValueUpper = 0.69,     // 3
                warmerValueLower = 0.69,    warmerValueUpper = 0.85,   // 4
                warmestValueLower = 0.85,   warmestValueUpper = 1.0,  // 5

        driestValueLower = 0.0,    driestValueUpper  = 0.27, // 0
                drierValueLower = 0.27,    drierValueUpper   = 0.4,  // 1
                dryValueLower = 0.4,       dryValueUpper     = 0.6,  // 2
                wetValueLower = 0.6,       wetValueUpper     = 0.8,  // 3
                wetterValueLower = 0.8,    wetterValueUpper  = 0.9,  // 4
                wettestValueLower = 0.9,   wettestValueUpper = 1.0;  // 5

        /**
         * The default biome table to use with parts of biome codes from {@link #biomeCodeData}. Biomes are assigned by
         * heat and moisture for the first 36 of 61 elements (coldest to warmest for each group of 6, with the first
         * group as the dryest and the last group the wettest), then the next 6 are for coastlines (coldest to warmest),
         * then rivers (coldest to warmest), then lakes (coldest to warmest). The last is reserved for empty space.
         * <br>
         * Unlike with {@link SimpleBiomeMapper}, you cannot use a biome code directly from biomeCodeData as an index
         * into this in almost any case; you should pass the biome code to one of the extract methods.
         * {@link #extractBiomeA(int)} or {@link #extractBiomeB(int)} will work if you want a biome name, or
         * {@link #extractPartA(int)} or {@link #extractPartB(int)} should be used if you want a non-coded int that
         * represents one of the biomes' indices into something like this. You can also get the amount by which biome B
         * is affecting biome A with {@link #extractMixAmount(int)}.
         */
        public static final String[] biomeTable = {
                //COLDEST //COLDER        //COLD            //HOT                  //HOTTER              //HOTTEST
                "Ice",    "Ice",          "Grassland",      "Desert",              "Desert",             "Desert",             //DRYEST
                "Ice",    "Tundra",       "Grassland",      "Grassland",           "Desert",             "Desert",             //DRYER
                "Ice",    "Tundra",       "Woodland",       "Woodland",            "Savanna",            "Desert",             //DRY
                "Ice",    "Tundra",       "SeasonalForest", "SeasonalForest",      "Savanna",            "Savanna",            //WET
                "Ice",    "Tundra",       "BorealForest",   "TemperateRainforest", "TropicalRainforest", "Savanna",            //WETTER
                "Ice",    "BorealForest", "BorealForest",   "TemperateRainforest", "TropicalRainforest", "TropicalRainforest", //WETTEST
                "Rocky",  "Rocky",        "Beach",          "Beach",               "Beach",              "Beach",              //COASTS
                "Ice",    "River",        "River",          "River",               "River",              "River",              //RIVERS
                "Ice",    "River",        "River",          "River",               "River",              "River",              //LAKES
                "Ocean",  "Ocean",        "Ocean",          "Ocean",               "Ocean",              "Ocean",              //OCEAN
                "Empty",                                                                                                       //SPACE
        };

        /**
         * Gets the int stored in part A of the given biome code, which can be used as an index into other collections.
         * This int should almost always range from 0 to 60 (both inclusive), so collections this is used as an index
         * for should have a length of at least 61.
         * @param biomeCode a biome code that was probably received from {@link #biomeCodeData}
         * @return an int stored in the biome code's part A; almost always between 0 and 60, inclusive.
         */
        public int extractPartA(int biomeCode)
        {
            return biomeCode & 1023;
        }
        /**
         * Gets a String from {@link #biomeTable} that names the appropriate biome in part A of the given biome code.
         * @param biomeCode a biome code that was probably received from {@link #biomeCodeData}
         * @return a String that names the biome in part A of biomeCode, or "Empty" if none can be found
         */
        public String extractBiomeA(int biomeCode)
        {
            biomeCode &= 1023;
            if(biomeCode < 60)
                return biomeTable[biomeCode];
            return "Empty";
        }
        /**
         * Gets the int stored in part B of the given biome code, which can be used as an index into other collections.
         * This int should almost always range from 0 to 60 (both inclusive), so collections this is used as an index
         * for should have a length of at least 61.
         * @param biomeCode a biome code that was probably received from {@link #biomeCodeData}
         * @return an int stored in the biome code's part B; almost always between 0 and 60, inclusive.
         */
        public int extractPartB(int biomeCode)
        {
            return (biomeCode >>> 10) & 1023;
        }

        /**
         * Gets a String from {@link #biomeTable} that names the appropriate biome in part B of the given biome code.
         * @param biomeCode a biome code that was probably received from {@link #biomeCodeData}
         * @return a String that names the biome in part B of biomeCode, or "Ocean" if none can be found
         */
        public String extractBiomeB(int biomeCode)
        {
            biomeCode = (biomeCode >>> 10) & 1023;
            if(biomeCode < 60)
                return biomeTable[biomeCode];
            return "Empty";
        }

        /**
         * This gets the portion of a biome code that represents the amount of mixing between two biomes.
         * Biome codes are normally obtained from the {@link #biomeCodeData} field, and aren't very usable on their own
         * without calling methods like this, {@link #extractBiomeA(int)}, and {@link #extractBiomeB(int)}. This returns
         * a float between 0.0f (inclusive) and 1.0f (exclusive), with 0.0f meaning biome B has no effect on an area and
         * biome A is the only one used, 0.5f meaning biome A and biome B have equal effect, and 0.75f meaning biome B
         * has most of the effect, three-fourths of the area, and biome A has less, one-fourth of the area.
         * @param biomeCode a biome code that was probably received from {@link #biomeCodeData}
         * @return a float between 0.0f (inclusive) and 1.0f (exclusive) representing mixing of biome B into biome A
         */
        public float extractMixAmount(int biomeCode)
        {
            return (biomeCode >>> 20) * 0x1p-10f;
        }

        /**
         * Simple constructor; pretty much does nothing. Make sure to call {@link #makeBiomes(WorldMapGenerator)} before
         * using fields like {@link #biomeCodeData}.
         */
        public DetailedBiomeMapper()
        {
            heatCodeData = null;
            moistureCodeData = null;
            biomeCodeData = null;
        }

        /**
         * Analyzes the last world produced by the given WorldMapGenerator and uses all of its generated information to
         * assign biome codes for each cell (along with heat and moisture codes). After calling this, biome codes can be
         * taken from {@link #biomeCodeData} and used with methods in this class like {@link #extractBiomeA(int)},
         * {@link #extractBiomeB(int)}, and {@link #extractMixAmount(int)} to find the two dominant biomes in an area,
         * called biome A and biome B, and the mix amount, for finding how much biome B affects biome A.
         * @param world a WorldMapGenerator that should have generated at least one map; it may be at any zoom
         */
        @Override
        public void makeBiomes(WorldMapGenerator world) {
            if(world == null || world.width <= 0 || world.height <= 0)
                return;
            if(heatCodeData == null || (heatCodeData.length != world.width || heatCodeData[0].length != world.height))
                heatCodeData = new int[world.width][world.height];
            if(moistureCodeData == null || (moistureCodeData.length != world.width || moistureCodeData[0].length != world.height))
                moistureCodeData = new int[world.width][world.height];
            if(biomeCodeData == null || (biomeCodeData.length != world.width || biomeCodeData[0].length != world.height))
                biomeCodeData = new int[world.width][world.height];
            final int[][] heightCodeData = world.heightCodeData;
            final double[][] heatData = world.heatData, moistureData = world.moistureData, heightData = world.heightData;
            int hc, mc, heightCode, bc;
            double hot, moist, high, i_hot = 1.0 / world.maxHeat;
            for (int x = 0; x < world.width; x++) {
                for (int y = 0; y < world.height; y++) {

                    heightCode = heightCodeData[x][y];
                    if(heightCode == 1000) {
                        biomeCodeData[x][y] = 60;
                        continue;
                    }
                    hot = heatData[x][y];
                    moist = moistureData[x][y];
                    high = heightData[x][y];
//                    fresh = world.freshwaterData[x][y];
                    boolean isLake = false,//world.generateRivers && heightCode >= 4 && fresh > 0.65 && fresh + moist * 2.35 > 2.75,//world.partialLakeData.contains(x, y) && heightCode >= 4,
                            isRiver = false;//world.generateRivers && !isLake && heightCode >= 4 && fresh > 0.55 && fresh + moist * 2.2 > 2.15;//world.partialRiverData.contains(x, y) && heightCode >= 4;
                    if (moist >= (wettestValueUpper - (wetterValueUpper - wetterValueLower) * 0.2)) {
                        mc = 5;
                    } else if (moist >= (wetterValueUpper - (wetValueUpper - wetValueLower) * 0.2)) {
                        mc = 4;
                    } else if (moist >= (wetValueUpper - (dryValueUpper - dryValueLower) * 0.2)) {
                        mc = 3;
                    } else if (moist >= (dryValueUpper - (drierValueUpper - drierValueLower) * 0.2)) {
                        mc = 2;
                    } else if (moist >= (drierValueUpper - (driestValueUpper) * 0.2)) {
                        mc = 1;
                    } else {
                        mc = 0;
                    }

                    if (hot >= (warmestValueUpper - (warmerValueUpper - warmerValueLower) * 0.2) * i_hot) {
                        hc = 5;
                    } else if (hot >= (warmerValueUpper - (warmValueUpper - warmValueLower) * 0.2) * i_hot) {
                        hc = 4;
                    } else if (hot >= (warmValueUpper - (coldValueUpper - coldValueLower) * 0.2) * i_hot) {
                        hc = 3;
                    } else if (hot >= (coldValueUpper - (colderValueUpper - colderValueLower) * 0.2) * i_hot) {
                        hc = 2;
                    } else if (hot >= (colderValueUpper - (coldestValueUpper) * 0.2) * i_hot) {
                        hc = 1;
                    } else {
                        hc = 0;
                    }

                    heatCodeData[x][y] = hc;
                    moistureCodeData[x][y] = mc;
                    bc = heightCode < 4 ? hc + 54 // 54 == 9 * 6, 9 is used for Ocean groups
                            : isLake ? hc + 48 : (isRiver ? hc + 42 : ((heightCode == 4) ? hc + 36 : hc + mc * 6));

                    if(heightCode < 4) {
                        mc = 9;
                    }
                    else if (moist >= (wetterValueUpper + (wettestValueUpper - wettestValueLower) * 0.2)) {
                        mc = 5;
                    } else if (moist >= (wetValueUpper + (wetterValueUpper - wetterValueLower) * 0.2)) {
                        mc = 4;
                    } else if (moist >= (dryValueUpper + (wetValueUpper - wetValueLower) * 0.2)) {
                        mc = 3;
                    } else if (moist >= (drierValueUpper + (dryValueUpper - dryValueLower) * 0.2)) {
                        mc = 2;
                    } else if (moist >= (driestValueUpper + (drierValueUpper - drierValueLower) * 0.2)) {
                        mc = 1;
                    } else {
                        mc = 0;
                    }

                    if (hot >= (warmerValueUpper + (warmestValueUpper - warmestValueLower) * 0.2) * i_hot) {
                        hc = 5;
                    } else if (hot >= (warmValueUpper + (warmerValueUpper - warmerValueLower) * 0.2) * i_hot) {
                        hc = 4;
                    } else if (hot >= (coldValueUpper + (warmValueUpper - warmValueLower) * 0.2) * i_hot) {
                        hc = 3;
                    } else if (hot >= (colderValueUpper + (coldValueUpper - coldValueLower) * 0.2) * i_hot) {
                        hc = 2;
                    } else if (hot >= (coldestValueUpper + (colderValueUpper - colderValueLower) * 0.2) * i_hot) {
                        hc = 1;
                    } else {
                        hc = 0;
                    }

                    bc |= (hc + mc * 6) << 10;
                    if(heightCode < 4)
                        biomeCodeData[x][y] = bc | (int)((heightData[x][y] + 1.0) * 1000.0) << 20;
                    else if (isRiver || isLake)
                        biomeCodeData[x][y] = bc | (int)(moist * 358.4 + 665.0) << 20;
                    else
                        biomeCodeData[x][y] = bc | (int) ((heightCode == 4)
                                ? (sandUpper - high) * 10240.0 // multiplier affected by changes to sandLower
                                : TrigTools.sin_((high + moist) * (4.1 + high - hot)) * 512 + 512) << 20;
                }
            }
        }
    }
    
    /**
     * A concrete implementation of {@link WorldMapGenerator} that distorts the map as it nears the poles, expanding the
     * smaller-diameter latitude lines in extreme north and south regions so they take up the same space as the equator;
     * this counteracts certain artifacts that are common in Simplex noise world maps by using a 4D noise call to
     * generate terrain, using a normal 3D noise call's result as the extra 4th dimension. This generator allows
     * choosing a {@link Noise}, which is used for most of the generation. This is ideal for projecting onto a 3D
     * sphere, which could squash the poles to counteract the stretch this does. You might also want to produce an oval
     * map that more-accurately represents the changes in the diameter of a latitude line on a spherical world; you
     * should use {@link EllipticalMap} or {@link EllipticalHammerMap} for this.
     * {@link HyperellipticalMap} is also a nice option because it can project onto a shape between a
     * rectangle (like this class) and an ellipse (like EllipticalMap), with all-round sides.
     * <a href="http://squidpony.github.io/SquidLib/SphereWorld.png" >Example map</a>.
     */
    public static class SphereMap extends WorldMapGenerator {
        private double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final Noise terrain, heat, moisture, otherRidged, terrainLayered;
        public final double[][] xPositions,
                yPositions,
                zPositions;


        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Always makes a 256x128 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link SphereMap#SphereMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 256, 128, DEFAULT_NOISE, 1.0}.
         */
        public SphereMap() {
            this(0x1337BABE1337D00DL, 256, 128, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public SphereMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight,  DEFAULT_NOISE,1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public SphereMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public SphereMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public SphereMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed, the width/height of the map, and parameters for noise
         * generation (a {@link Noise} implementation, which is usually {@link Noise#instance}, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise#instance}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public SphereMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier) {
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[width][height];
            yPositions = new double[width][height];
            zPositions = new double[width][height];

            terrain = new Noise(seedA, (float) terrainFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 7));
            terrain.setFractalType(Noise.RIDGED_MULTI);
            otherRidged = new Noise(seedB, (float) otherFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 6));
            otherRidged.setFractalType(Noise.RIDGED_MULTI);
            terrainLayered = new Noise(seedB, (float) terrainRidgedFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 5));
            heat = new Noise(seedB, (float) heatFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 3), 0.75f, 1.333f);
            moisture = new Noise(seedB, (float) moistureFreq, Noise.SIMPLEX_FRACTAL, (int) (1 + octaveMultiplier * 4), 0.55f, 1f / 0.55f);
        }
        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }

        /**
         * Copies the SphereMap {@code other} to construct a new one that is exactly the same. References will only be
         * shared to Noise classes.
         * @param other a SphereMap to copy
         */
        public SphereMap(SphereMap other)
        {
            super(other);
            terrain = other.terrain;
            terrainLayered = other.terrainLayered;
            heat = other.heat;
            moisture = other.moisture;
            otherRidged = other.otherRidged;
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
        }

        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            boolean fresh = false;
            if(cacheA != stateA || cacheB != stateB || landMod != landModifier || heatMod != heatModifier)
            {
                minHeight = Double.POSITIVE_INFINITY;
                maxHeight = Double.NEGATIVE_INFINITY;
                minHeat0 = Double.POSITIVE_INFINITY;
                maxHeat0 = Double.NEGATIVE_INFINITY;
                minHeat1 = Double.POSITIVE_INFINITY;
                maxHeat1 = Double.NEGATIVE_INFINITY;
                minHeat = Double.POSITIVE_INFINITY;
                maxHeat = Double.NEGATIVE_INFINITY;
                minWet0 = Double.POSITIVE_INFINITY;
                maxWet0 = Double.NEGATIVE_INFINITY;
                minWet = Double.POSITIVE_INFINITY;
                maxWet = Double.NEGATIVE_INFINITY;
                cacheA = stateA;
                cacheB = stateB;
                fresh = true;
            }
            rng.setState(stateA, stateB);
            long seedA = rng.nextLong(), seedB = rng.nextLong(), seedC = rng.nextLong();
            int t;

            landModifier = (landMod <= 0) ? rng.nextDouble(0.29) + 0.91 : landMod;
            heatModifier = (heatMod <= 0) ? rng.nextDouble(0.45) * (rng.nextDouble()-0.5) + 1.1 : heatMod;

            double p,
                    ps, pc,
                    qs, qc,
                    h, temp,
                    i_w = 6.283185307179586 / width, i_h = 2.0 / (height+2.0),//(3.141592653589793) / (height+2.0),
                    xPos = startX, yPos, i_uw = usedWidth / (double)width, i_uh = usedHeight * i_h / (height+2.0);
            final double[] trigTable = new double[width << 1];
            for (int x = 0; x < width; x++, xPos += i_uw) {
                p = xPos * i_w + centerLongitude;
                // 0.7978845608028654 1.2533141373155001
                trigTable[x<<1]   = TrigTools.sin(p);// * 1.2533141373155001;
                trigTable[x<<1|1] = TrigTools.cos(p);// * 0.7978845608028654;
            }
            yPos = startY * i_h + i_uh;
            for (int y = 0; y < height; y++, yPos += i_uh) {
                qs = -1 + yPos;//-1.5707963267948966 + yPos;
                qc = TrigTools.cos(TrigTools.asin(qs));
                //qs = qs;
                //qs = TrigTools.sin(qs);
                for (int x = 0, xt = 0; x < width; x++) {
                    ps = trigTable[xt++] * qc;//TrigTools.sin(p);
                    pc = trigTable[xt++] * qc;//TrigTools.cos(p);
                    xPositions[x][y] = pc;
                    yPositions[x][y] = ps;
                    zPositions[x][y] = qs;
                    heightData[x][y] = (h = terrainLayered.getNoiseWithSeed(pc +
                                    terrain.getNoiseWithSeed(pc, ps, qs,seedB - seedA) * 0.5,
                            ps, qs, seedA) + landModifier - 1.0);
                    heatData[x][y] = (p = heat.getNoiseWithSeed(pc, ps
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs,seedB + seedC)
                            , qs, seedB));
                    moistureData[x][y] = (temp = moisture.getNoiseWithSeed(pc, ps, qs
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs, seedC + seedA)
                            , seedC));

                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                    if(fresh) {
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);

                        minHeat0 = Math.min(minHeat0, p);
                        maxHeat0 = Math.max(maxHeat0, p);

                        minWet0 = Math.min(minWet0, temp);
                        maxWet0 = Math.max(maxWet0, temp);
                    }
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);

            }
            double  heatDiff = 0.8 / (maxHeat0 - minHeat0),
                    wetDiff = 1.0 / (maxWet0 - minWet0),
                    hMod;
            yPos = startY * i_h + i_uh;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < height; y++, yPos += i_uh) {
                temp = Math.abs(yPos - 1.0);
                temp *= (2.4 - temp);
                temp = 2.2 - temp;
                for (int x = 0; x < width; x++) {
//                    heightData[x][y] = (h = (heightData[x][y] - minHeightActual) * heightDiff - 1.0);
//                    minHeightActual0 = Math.min(minHeightActual0, h);
//                    maxHeightActual0 = Math.max(maxHeightActual0, h);
                    h = heightData[x][y];
                    heightCodeData[x][y] = (t = codeHeight(h));
                    hMod = 1.0;
                    switch (t) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            h = 0.4;
                            hMod = 0.2;
                            break;
                        case 6:
                            h = -0.1 * (h - forestLower - 0.08);
                            break;
                        case 7:
                            h *= -0.25;
                            break;
                        case 8:
                            h *= -0.4;
                            break;
                        default:
                            h *= 0.05;
                    }
                    heatData[x][y] = (h = (((heatData[x][y] - minHeat0) * heatDiff * hMod) + h + 0.6) * temp);
                    if (fresh) {
                        ps = Math.min(ps, h); //minHeat0
                        pc = Math.max(pc, h); //maxHeat0
                    }
                }
            }
            if(fresh)
            {
                minHeat1 = ps;
                maxHeat1 = pc;
            }
            heatDiff = heatModifier / (maxHeat1 - minHeat1);
            qs = Double.POSITIVE_INFINITY;
            qc = Double.NEGATIVE_INFINITY;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;


            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat1) * heatDiff));
                    moistureData[x][y] = (temp = (moistureData[x][y] - minWet0) * wetDiff);
                    if (fresh) {
                        qs = Math.min(qs, h);
                        qc = Math.max(qc, h);
                        ps = Math.min(ps, temp);
                        pc = Math.max(pc, temp);
                    }
                }
            }
            if(fresh)
            {
                minHeat = qs;
                maxHeat = qc;
                minWet = ps;
                maxWet = pc;
            }
            
            /*
            if(generateRivers) {
                if (fresh) {
                    addRivers();
                    riverData.connect8way().thin().thin();
                    lakeData.connect8way().thin();
                    partialRiverData.remake(riverData);
                    partialLakeData.remake(lakeData);
                } else {
                    partialRiverData.remake(riverData);
                    partialLakeData.remake(lakeData);
                    int stx = Math.min(Math.max((zoomStartX >> zoom) - (width >> 2), 0), width),
                            sty = Math.min(Math.max((zoomStartY >> zoom) - (height >> 2), 0), height);
                    for (int i = 1; i <= zoom; i++) {
                        int stx2 = (startCacheX.get(i) - startCacheX.get(i - 1)) << (i - 1),
                                sty2 = (startCacheY.get(i) - startCacheY.get(i - 1)) << (i - 1);
                        //(zoomStartX >> zoom) - (width >> 1 + zoom), (zoomStartY >> zoom) - (height >> 1 + zoom)

//                        Map is 200x100, GreasedRegions have that size too.
//                        Zoom 0 only allows 100,50 as the center, 0,0 as the corner
//                        Zoom 1 allows 100,50 to 300,150 as the center (x2 coordinates), 0,0 to 200,100 (refers to 200,100) as the corner
//                        Zoom 2 allows 100,50 to 700,350 as the center (x4 coordinates), 0,0 to 200,100 (refers to 600,300) as the corner


                        System.out.printf("zoomStartX: %d zoomStartY: %d, stx: %d sty: %d, stx2: %d, sty2: %d\n", zoomStartX, zoomStartY, stx, sty, stx2, sty2);
                        if ((i & 3) == 3) {
                            partialRiverData.zoom(stx, sty).connect8way();
                            partialRiverData.or(workingData.remake(partialRiverData).fringe().quasiRandomRegion(0.4));
                            partialLakeData.zoom(stx, sty).connect8way();
                            partialLakeData.or(workingData.remake(partialLakeData).fringe().quasiRandomRegion(0.55));
                        } else {
                            partialRiverData.zoom(stx, sty).connect8way().thin();
                            partialRiverData.or(workingData.remake(partialRiverData).fringe().quasiRandomRegion(0.5));
                            partialLakeData.zoom(stx, sty).connect8way().thin();
                            partialLakeData.or(workingData.remake(partialLakeData).fringe().quasiRandomRegion(0.7));
                        }
                        //stx = (width >> 1) ;//Math.min(Math.max(, 0), width);
                        //sty = (height >> 1);//Math.min(Math.max(, 0), height);
                    }
                    System.out.println();
                }
            }
            */
        }
    }
    /**
     * A concrete implementation of {@link WorldMapGenerator} that projects the world map onto an ellipse that should be
     * twice as wide as it is tall (although you can stretch it by width and height that don't have that ratio).
     * This uses the <a href="https://en.wikipedia.org/wiki/Mollweide_projection">Mollweide projection</a>.
     * <a href="http://squidpony.github.io/SquidLib/EllipseWorld.png" >Example map</a>.
     */
    public static class EllipticalMap extends WorldMapGenerator {
        protected double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final Noise terrain, heat, moisture, otherRidged, terrainLayered;
        public final double[][] xPositions,
                yPositions,
                zPositions;
        protected final int[] edges;


        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Always makes a 200x100 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link EllipticalMap#EllipticalMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 200, 100, DEFAULT_NOISE, 1.0}.
         */
        public EllipticalMap() {
            this(0x1337BABE1337D00DL, 200, 100, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public EllipticalMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight,  DEFAULT_NOISE,1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public EllipticalMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public EllipticalMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail. The suggested Noise
         * implementation to use is {@link Noise#instance}.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public EllipticalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed, the width/height of the map, and parameters for noise generation (a
         * {@link Noise} implementation, where {@link Noise#instance} is suggested, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in.  Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public EllipticalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier) {
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[width][height];
            yPositions = new double[width][height];
            zPositions = new double[width][height];
            edges = new int[height << 1];

            terrain = new Noise(seedA, (float) terrainFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 7));
            terrain.setFractalType(Noise.RIDGED_MULTI);
            otherRidged = new Noise(seedB, (float) otherFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 6));
            otherRidged.setFractalType(Noise.RIDGED_MULTI);
            terrainLayered = new Noise(seedB, (float) terrainRidgedFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 5));
            heat = new Noise(seedB, (float) heatFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 3), 0.75f, 1.333f);
            moisture = new Noise(seedB, (float) moistureFreq, Noise.SIMPLEX_FRACTAL, (int) (1 + octaveMultiplier * 4), 0.55f, 1f / 0.55f);
        }

        /**
         * Copies the EllipticalMap {@code other} to construct a new one that is exactly the same. References will only
         * be shared to Noise classes.
         * @param other an EllipticalMap to copy
         */
        public EllipticalMap(EllipticalMap other)
        {
            super(other);
            terrain = other.terrain;
            terrainLayered = other.terrainLayered;
            heat = other.heat;
            moisture = other.moisture;
            otherRidged = other.otherRidged;
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
            edges = Arrays.copyOf(other.edges, other.edges.length);
        }

        @Override
        public int wrapX(final int x, int y) {
            y = Math.max(0, Math.min(y, height - 1));
            if(x < edges[y << 1])
                return edges[y << 1 | 1];
            else if(x > edges[y << 1 | 1])
                return edges[y << 1];
            else return x;
        }

        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }

        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            boolean fresh = false;
            if(cacheA != stateA || cacheB != stateB || landMod != landModifier || heatMod != heatModifier)
            {
                minHeight = Double.POSITIVE_INFINITY;
                maxHeight = Double.NEGATIVE_INFINITY;
                minHeat0 = Double.POSITIVE_INFINITY;
                maxHeat0 = Double.NEGATIVE_INFINITY;
                minHeat1 = Double.POSITIVE_INFINITY;
                maxHeat1 = Double.NEGATIVE_INFINITY;
                minHeat = Double.POSITIVE_INFINITY;
                maxHeat = Double.NEGATIVE_INFINITY;
                minWet0 = Double.POSITIVE_INFINITY;
                maxWet0 = Double.NEGATIVE_INFINITY;
                minWet = Double.POSITIVE_INFINITY;
                maxWet = Double.NEGATIVE_INFINITY;
                cacheA = stateA;
                cacheB = stateB;
                fresh = true;
            }
            rng.setState(stateA, stateB);
            long seedA = rng.nextLong(), seedB = rng.nextLong(), seedC = rng.nextLong();
            int t;

            landModifier = (landMod <= 0) ? rng.nextDouble(0.2) + 0.91 : landMod;
            heatModifier = (heatMod <= 0) ? rng.nextDouble(0.45) * (rng.nextDouble()-0.5) + 1.1 : heatMod;

            double p,
                    ps, pc,
                    qs, qc,
                    h, temp, yPos, xPos,
                    i_uw = usedWidth / (double)width,
                    i_uh = usedHeight / (double)height,
                    th, thx, thy, lon, lat, ipi = 0.99999 / Math.PI,
                    rx = width * 0.25, irx = 1.0 / rx, hw = width * 0.5,
                    ry = height * 0.5, iry = 1.0 / ry;

            yPos = startY - ry;
            for (int y = 0; y < height; y++, yPos += i_uh) {
                thx = TrigTools.asin((yPos) * iry);
                lon = (thx == Math.PI * 0.5 || thx == Math.PI * -0.5) ? thx : Math.PI * irx * 0.5 / TrigTools.cos(thx);
                thy = thx * 2.0;
                lat = TrigTools.asin((thy + TrigTools.sin(thy)) * ipi);

                qc = TrigTools.cos(lat);
                qs = TrigTools.sin(lat);

                boolean inSpace = true;
                xPos = startX;
                for (int x = 0; x < width; x++, xPos += i_uw) {
                    th = lon * (xPos - hw);
                    if(th < -3.141592653589793 || th > 3.141592653589793) {
                        heightCodeData[x][y] = 10000;
                        inSpace = true;
                        continue;
                    }
                    if(inSpace)
                    {
                        inSpace = false;
                        edges[y << 1] = x;
                    }
                    edges[y << 1 | 1] = x;
                    th += centerLongitude;
                    ps = TrigTools.sin(th) * qc;
                    pc = TrigTools.cos(th) * qc;
                    xPositions[x][y] = pc;
                    yPositions[x][y] = ps;
                    zPositions[x][y] = qs;
                    heightData[x][y] = (h = terrainLayered.getNoiseWithSeed(pc +
                                    terrain.getNoiseWithSeed(pc, ps, qs,seedB - seedA) * 0.5,
                            ps, qs, seedA) + landModifier - 1.0);
                    heatData[x][y] = (p = heat.getNoiseWithSeed(pc, ps
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs,seedB + seedC)
                            , qs, seedB));
                    moistureData[x][y] = (temp = moisture.getNoiseWithSeed(pc, ps, qs
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs, seedC + seedA)
                            , seedC));
                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                    if(fresh) {
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);

                        minHeat0 = Math.min(minHeat0, p);
                        maxHeat0 = Math.max(maxHeat0, p);

                        minWet0 = Math.min(minWet0, temp);
                        maxWet0 = Math.max(maxWet0, temp);
                    }
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);

            }
            double  heatDiff = 0.8 / (maxHeat0 - minHeat0),
                    wetDiff = 1.0 / (maxWet0 - minWet0),
                    hMod,
                    halfHeight = (height - 1) * 0.5, i_half = 1.0 / halfHeight;
            yPos = startY + i_uh;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < height; y++, yPos += i_uh) {
                temp = Math.abs(yPos - halfHeight) * i_half;
                temp *= (2.4 - temp);
                temp = 2.2 - temp;
                for (int x = 0; x < width; x++) {
                    h = heightData[x][y];
                    if(heightCodeData[x][y] == 10000) {
                        heightCodeData[x][y] = 1000;
                        continue;
                    }
                    else
                        heightCodeData[x][y] = (t = codeHeight(h));
                    hMod = 1.0;
                    switch (t) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            h = 0.4;
                            hMod = 0.2;
                            break;
                        case 6:
                            h = -0.1 * (h - forestLower - 0.08);
                            break;
                        case 7:
                            h *= -0.25;
                            break;
                        case 8:
                            h *= -0.4;
                            break;
                        default:
                            h *= 0.05;
                    }
                    heatData[x][y] = (h = (((heatData[x][y] - minHeat0) * heatDiff * hMod) + h + 0.6) * temp);
                    if (fresh) {
                        ps = Math.min(ps, h); //minHeat0
                        pc = Math.max(pc, h); //maxHeat0
                    }
                }
            }
            if(fresh)
            {
                minHeat1 = ps;
                maxHeat1 = pc;
            }
            heatDiff = heatModifier / (maxHeat1 - minHeat1);
            qs = Double.POSITIVE_INFINITY;
            qc = Double.NEGATIVE_INFINITY;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;


            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat1) * heatDiff));
                    moistureData[x][y] = (temp = (moistureData[x][y] - minWet0) * wetDiff);
                    if (fresh) {
                        qs = Math.min(qs, h);
                        qc = Math.max(qc, h);
                        ps = Math.min(ps, temp);
                        pc = Math.max(pc, temp);
                    }
                }
            }
            if(fresh)
            {
                minHeat = qs;
                maxHeat = qc;
                minWet = ps;
                maxWet = pc;
            }
            
        }
    }
    /**
     * A concrete implementation of {@link WorldMapGenerator} that imitates an infinite-distance perspective view of a
     * world, showing only one hemisphere, that should be as wide as it is tall (its outline is a circle). This uses an
     * <a href="https://en.wikipedia.org/wiki/Orthographic_projection_in_cartography">Orthographic projection</a> with
     * the latitude always at the equator.
     * <a href="http://squidpony.github.io/SquidLib/SpaceViewMap.png" >Example map, showing circular shape as if viewed
     * from afar</a>
     */
    public static class SpaceViewMap extends WorldMapGenerator {

        protected double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final Noise terrain, heat, moisture, otherRidged, terrainLayered;
        public final double[][] xPositions,
                yPositions,
                zPositions;
        protected final int[] edges;

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Always makes a 100x100 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link SpaceViewMap#SpaceViewMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 100, 100, DEFAULT_NOISE, 1.0}.
         */
        public SpaceViewMap() {
            this(0x1337BABE1337D00DL, 100, 100, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public SpaceViewMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight,  DEFAULT_NOISE,1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public SpaceViewMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public SpaceViewMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public SpaceViewMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed, the width/height of the map, and parameters for noise
         * generation (a {@link Noise} implementation, which is usually {@link Noise#instance}, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public SpaceViewMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier) {
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[width][height];
            yPositions = new double[width][height];
            zPositions = new double[width][height];
            edges = new int[height << 1];
            terrain = new Noise(seedA, (float) terrainFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 7));
            terrain.setFractalType(Noise.RIDGED_MULTI);
            otherRidged = new Noise(seedB, (float) otherFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 6));
            otherRidged.setFractalType(Noise.RIDGED_MULTI);
            terrainLayered = new Noise(seedB, (float) terrainRidgedFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 5));
            heat = new Noise(seedB, (float) heatFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 3), 0.75f, 1.333f);
            moisture = new Noise(seedB, (float) moistureFreq, Noise.SIMPLEX_FRACTAL, (int) (1 + octaveMultiplier * 4), 0.55f, 1f / 0.55f);
        }

        /**
         * Copies the SpaceViewMap {@code other} to construct a new one that is exactly the same. References will only
         * be shared to Noise classes.
         * @param other a SpaceViewMap to copy
         */
        public SpaceViewMap(SpaceViewMap other)
        {
            super(other);
            terrain = other.terrain;
            terrainLayered = other.terrainLayered;
            heat = other.heat;
            moisture = other.moisture;
            otherRidged = other.otherRidged;
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
            edges = Arrays.copyOf(other.edges, other.edges.length);
        }
        
        @Override
        public int wrapX(int x, int y) {
            y = Math.max(0, Math.min(y, height - 1));
            return Math.max(edges[y << 1], Math.min(x, edges[y << 1 | 1]));
        }

        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }

        //private static final double root2 = Math.sqrt(2.0), inverseRoot2 = 1.0 / root2, halfInverseRoot2 = 0.5 / root2;

        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            boolean fresh = false;
            if(cacheA != stateA || cacheB != stateB || landMod != landModifier || heatMod != heatModifier)
            {
                minHeight = Double.POSITIVE_INFINITY;
                maxHeight = Double.NEGATIVE_INFINITY;
                minHeightActual = Double.POSITIVE_INFINITY;
                maxHeightActual = Double.NEGATIVE_INFINITY;
                minHeat0 = Double.POSITIVE_INFINITY;
                maxHeat0 = Double.NEGATIVE_INFINITY;
                minHeat1 = Double.POSITIVE_INFINITY;
                maxHeat1 = Double.NEGATIVE_INFINITY;
                minHeat = Double.POSITIVE_INFINITY;
                maxHeat = Double.NEGATIVE_INFINITY;
                minWet0 = Double.POSITIVE_INFINITY;
                maxWet0 = Double.NEGATIVE_INFINITY;
                minWet = Double.POSITIVE_INFINITY;
                maxWet = Double.NEGATIVE_INFINITY;
                cacheA = stateA;
                cacheB = stateB;
                fresh = true;
            }
            rng.setState(stateA, stateB);
            long seedA = rng.nextLong(), seedB = rng.nextLong(), seedC = rng.nextLong();
            int t;

            landModifier = (landMod <= 0) ? rng.nextDouble(0.2) + 0.91 : landMod;
            heatModifier = (heatMod <= 0) ? rng.nextDouble(0.45) * (rng.nextDouble()-0.5) + 1.1 : heatMod;

            double p,
                    ps, pc,
                    qs, qc,
                    h, temp, yPos, xPos, iyPos, ixPos,
                    i_uw = usedWidth / (double)width,
                    i_uh = usedHeight / (double)height,
                    th, lon, lat, rho,
                    rx = width * 0.5, irx = i_uw / rx,
                    ry = height * 0.5, iry = i_uh / ry;

            yPos = startY - ry;
            iyPos = yPos / ry;
            for (int y = 0; y < height; y++, yPos += i_uh, iyPos += iry) {

                boolean inSpace = true;
                xPos = startX - rx;
                ixPos = xPos / rx;
                for (int x = 0; x < width; x++, xPos += i_uw, ixPos += irx) {
                    rho = Math.sqrt(ixPos * ixPos + iyPos * iyPos);
                    if(rho > 1.0) {
                        heightCodeData[x][y] = 10000;
                        inSpace = true;
                        continue;
                    }
                    if(inSpace)
                    {
                        inSpace = false;
                        edges[y << 1] = x;
                    }
                    edges[y << 1 | 1] = x;
                    th = TrigTools.asin(rho); // c
                    lat = TrigTools.asin(iyPos);
                    lon = centerLongitude + TrigTools.atan2(ixPos * rho, rho * TrigTools.cos(th));

                    qc = TrigTools.cos(lat);
                    qs = TrigTools.sin(lat);

                    pc = TrigTools.cos(lon) * qc;
                    ps = TrigTools.sin(lon) * qc;

                    xPositions[x][y] = pc;
                    yPositions[x][y] = ps;
                    zPositions[x][y] = qs;
                    heightData[x][y] = (h = terrainLayered.getNoiseWithSeed(pc +
                                    terrain.getNoiseWithSeed(pc, ps, qs,seedB - seedA) * 0.5,
                            ps, qs, seedA) + landModifier - 1.0);
//                    heightData[x][y] = (h = terrain4D.getNoiseWithSeed(pc, ps, qs,
//                            (terrainLayered.getNoiseWithSeed(pc, ps, qs, seedB - seedA)
//                                    + terrain.getNoiseWithSeed(pc, ps, qs, seedC - seedB)) * 0.5,
//                            seedA) * landModifier);
                    heatData[x][y] = (p = heat.getNoiseWithSeed(pc, ps
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs,seedB + seedC)
                            , qs, seedB));
                    moistureData[x][y] = (temp = moisture.getNoiseWithSeed(pc, ps, qs
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs, seedC + seedA)
                            , seedC));
                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                    if(fresh) {
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);

                        minHeat0 = Math.min(minHeat0, p);
                        maxHeat0 = Math.max(maxHeat0, p);

                        minWet0 = Math.min(minWet0, temp);
                        maxWet0 = Math.max(maxWet0, temp);
                    }
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);

            }
            double  heatDiff = 0.8 / (maxHeat0 - minHeat0),
                    wetDiff = 1.0 / (maxWet0 - minWet0),
                    hMod,
                    halfHeight = (height - 1) * 0.5, i_half = 1.0 / halfHeight;
            yPos = startY + i_uh;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < height; y++, yPos += i_uh) {
                temp = Math.abs(yPos - halfHeight) * i_half;
                temp *= (2.4 - temp);
                temp = 2.2 - temp;
                for (int x = 0; x < width; x++) {
                    h = heightData[x][y];
                    if(heightCodeData[x][y] == 10000) {
                        heightCodeData[x][y] = 1000;
                        continue;
                    }
                    else
                        heightCodeData[x][y] = (t = codeHeight(h));
                    hMod = 1.0;
                    switch (t) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            h = 0.4;
                            hMod = 0.2;
                            break;
                        case 6:
                            h = -0.1 * (h - forestLower - 0.08);
                            break;
                        case 7:
                            h *= -0.25;
                            break;
                        case 8:
                            h *= -0.4;
                            break;
                        default:
                            h *= 0.05;
                    }
                    heatData[x][y] = (h = (((heatData[x][y] - minHeat0) * heatDiff * hMod) + h + 0.6) * temp);
                    if (fresh) {
                        ps = Math.min(ps, h); //minHeat0
                        pc = Math.max(pc, h); //maxHeat0
                    }
                }
            }
            if(fresh)
            {
                minHeat1 = ps;
                maxHeat1 = pc;
            }
            heatDiff = heatModifier / (maxHeat1 - minHeat1);
            qs = Double.POSITIVE_INFINITY;
            qc = Double.NEGATIVE_INFINITY;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;


            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat1) * heatDiff));
                    moistureData[x][y] = (temp = (moistureData[x][y] - minWet0) * wetDiff);
                    if (fresh) {
                        qs = Math.min(qs, h);
                        qc = Math.max(qc, h);
                        ps = Math.min(ps, temp);
                        pc = Math.max(pc, temp);
                    }
                }
            }
            if(fresh)
            {
                minHeat = qs;
                maxHeat = qc;
                minWet = ps;
                maxWet = pc;
            }
            
        }
    }
    /**
     * A concrete implementation of {@link WorldMapGenerator} that projects the world map onto a shape that resembles a
     * mix part-way between an ellipse and a rectangle. This is an equal-area projection, like EllipticalMap.
     * This has less distortion around all the edges than the other maps here.
     * This uses the <a href="https://en.wikipedia.org/wiki/Tobler_hyperelliptical_projection">Tobler hyperelliptical projection</a>.
     * <a href="">Example map</a>
     */
    public static class HyperellipticalMap extends WorldMapGenerator {

        protected double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final Noise terrain, heat, moisture, otherRidged, terrainLayered;
        public final double[][] xPositions,
                yPositions,
                zPositions;
        protected final int[] edges;
        private final double alpha, kappa, epsilon;
        private final double[] Z;


        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Always makes a 200x100 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link HyperellipticalMap#HyperellipticalMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 200, 100, DEFAULT_NOISE, 1.0}.
         * <a href="http://squidpony.github.io/SquidLib/HyperellipseWorld.png" >Example map, showing special shape</a>
         */
        public HyperellipticalMap() {
            this(0x1337BABE1337D00DL, 200, 100, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public HyperellipticalMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight,  DEFAULT_NOISE,1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public HyperellipticalMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public HyperellipticalMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail. The suggested Noise
         * implementation to use is {@link Noise#instance}.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public HyperellipticalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed, the width/height of the map, and parameters for noise generation (a
         * {@link Noise} implementation, where {@link Noise#instance} is suggested, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public HyperellipticalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier){
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, octaveMultiplier, 0.0625, 2.5);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape.
         * Takes an initial seed, the width/height of the map, and parameters for noise generation (a
         * {@link Noise} implementation, where {@link Noise#instance} is suggested, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         * @param alpha one of the Tobler parameters;  0.0625 is the default and this can range from 0.0 to 1.0 at least
         * @param kappa one of the Tobler parameters; 2.5 is the default but 2.0-5.0 range values are also often used
         */
        public HyperellipticalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator,
                                  double octaveMultiplier, double alpha, double kappa){
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[width][height];
            yPositions = new double[width][height];
            zPositions = new double[width][height];
            edges = new int[height << 1];
            terrain = new Noise(seedA, (float) terrainFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 7));
            terrain.setFractalType(Noise.RIDGED_MULTI);
            otherRidged = new Noise(seedB, (float) otherFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 6));
            otherRidged.setFractalType(Noise.RIDGED_MULTI);
            terrainLayered = new Noise(seedB, (float) terrainRidgedFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 5));
            heat = new Noise(seedB, (float) heatFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 3), 0.75f, 1.333f);
            moisture = new Noise(seedB, (float) moistureFreq, Noise.SIMPLEX_FRACTAL, (int) (1 + octaveMultiplier * 4), 0.55f, 1f / 0.55f);
            this.alpha = alpha;
            this.kappa = kappa;
            this.Z = new double[height << 2];
            this.epsilon = ProjectionTools.simpsonIntegrateHyperellipse(0.0, 1.0, 0.25 / height, kappa);
            ProjectionTools.simpsonODESolveHyperellipse(1, this.Z, 0.25 / height, alpha, kappa, epsilon);
        }
        /**
         * Copies the HyperellipticalMap {@code other} to construct a new one that is exactly the same. References will only
         * be shared to Noise classes.
         * @param other a HyperellipticalMap to copy
         */
        public HyperellipticalMap(HyperellipticalMap other)
        {
            super(other);
            terrain = other.terrain;
            terrainLayered = other.terrainLayered;
            heat = other.heat;
            moisture = other.moisture;
            otherRidged = other.otherRidged;
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
            edges = Arrays.copyOf(other.edges, other.edges.length);
            alpha = other.alpha;
            kappa = other.kappa;
            epsilon = other.epsilon;
            Z = Arrays.copyOf(other.Z, other.Z.length);
        }


        @Override
        public int wrapX(final int x, int y) {
            y = Math.max(0, Math.min(y, height - 1));
            if(x < edges[y << 1])
                return edges[y << 1 | 1];
            else if(x > edges[y << 1 | 1])
                return edges[y << 1];
            else return x;
        }

        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }

        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            boolean fresh = false;
            if(cacheA != stateA || cacheB != stateB || landMod != landModifier || heatMod != heatModifier)
            {
                minHeight = Double.POSITIVE_INFINITY;
                maxHeight = Double.NEGATIVE_INFINITY;
                minHeightActual = Double.POSITIVE_INFINITY;
                maxHeightActual = Double.NEGATIVE_INFINITY;
                minHeat0 = Double.POSITIVE_INFINITY;
                maxHeat0 = Double.NEGATIVE_INFINITY;
                minHeat1 = Double.POSITIVE_INFINITY;
                maxHeat1 = Double.NEGATIVE_INFINITY;
                minHeat = Double.POSITIVE_INFINITY;
                maxHeat = Double.NEGATIVE_INFINITY;
                minWet0 = Double.POSITIVE_INFINITY;
                maxWet0 = Double.NEGATIVE_INFINITY;
                minWet = Double.POSITIVE_INFINITY;
                maxWet = Double.NEGATIVE_INFINITY;
                cacheA = stateA;
                cacheB = stateB;
                fresh = true;
            }
            rng.setState(stateA, stateB);
            long seedA = rng.nextLong(), seedB = rng.nextLong(), seedC = rng.nextLong();
            int t;

            landModifier = (landMod <= 0) ? rng.nextDouble(0.2) + 0.91 : landMod;
            heatModifier = (heatMod <= 0) ? rng.nextDouble(0.45) * (rng.nextDouble()-0.5) + 1.1 : heatMod;

            double p,
                    ps, pc,
                    qs, qc,
                    h, temp, yPos, xPos,
                    i_uw = usedWidth / (double)width,
                    i_uh = usedHeight / (double)height,
                    th, lon,
                    rx = width * 0.5, irx = Math.PI / rx, hw = width * 0.5,
                    ry = height * 0.5, iry = 1.0 / ry;

            yPos = startY - ry;
            for (int y = 0; y < height; y++, yPos += i_uh) {
//                thy = yPos * iry;//TrigTools.sin(thb);
//                thb = asin(thy);
//                thx = TrigTools.cos(thb);
//                //1.3265004 0.7538633073600218  1.326500428177002
//                lon = (thx == Math.PI * 0.5 || thx == Math.PI * -0.5) ? 0x1.0p100 : irx / (0.42223820031577125 * (1.0 + thx));
//                qs = (thb + (thx + 2.0) * thy) * 0.2800495767557787;
//                lat = asin(qs);
//
//                qc = TrigTools.cos(lat);

                lon = TrigTools.asin(Z[(int)(0.5 + Math.abs(yPos*iry)*(Z.length-1))])* Math.signum(yPos);
                qs = TrigTools.sin(lon);
                qc = TrigTools.cos(lon);

                boolean inSpace = true;
                xPos = startX - hw;
                for (int x = 0/*, xt = 0*/; x < width; x++, xPos += i_uw) {
                    //th = lon * xPos;
                    th = xPos * irx / Math.abs(alpha + (1-alpha)*ProjectionTools.hyperellipse(yPos * iry, kappa));
                    if(th < -3.141592653589793 || th > 3.141592653589793) {
                        //if(th < -2.0 || th > 2.0) {
                        heightCodeData[x][y] = 10000;
                        inSpace = true;
                        continue;
                    }
                    if(inSpace)
                    {
                        inSpace = false;
                        edges[y << 1] = x;
                    }
                    edges[y << 1 | 1] = x;
                    th += centerLongitude;
                    ps = TrigTools.sin(th) * qc;
                    pc = TrigTools.cos(th) * qc;
                    xPositions[x][y] = pc;
                    yPositions[x][y] = ps;
                    zPositions[x][y] = qs;
                    heightData[x][y] = (h = terrainLayered.getNoiseWithSeed(pc +
                                    terrain.getNoiseWithSeed(pc, ps, qs, seedB - seedA) * 0.5,
                            ps, qs, seedA) + landModifier - 1.0);
                    heatData[x][y] = (p = heat.getNoiseWithSeed(pc, ps
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs, seedB + seedC)
                            , qs, seedB));
                    moistureData[x][y] = (temp = moisture.getNoiseWithSeed(pc, ps, qs
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs, seedC + seedA)
                            , seedC));
                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                    if(fresh) {
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);

                        minHeat0 = Math.min(minHeat0, p);
                        maxHeat0 = Math.max(maxHeat0, p);

                        minWet0 = Math.min(minWet0, temp);
                        maxWet0 = Math.max(maxWet0, temp);
                    }
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);

            }
            double  heatDiff = 0.8 / (maxHeat0 - minHeat0),
                    wetDiff = 1.0 / (maxWet0 - minWet0),
                    hMod,
                    halfHeight = (height - 1) * 0.5, i_half = 1.0 / halfHeight;
            yPos = startY + i_uh;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < height; y++, yPos += i_uh) {
                temp = Math.abs(yPos - halfHeight) * i_half;
                temp *= (2.4 - temp);
                temp = 2.2 - temp;
                for (int x = 0; x < width; x++) {
                    h = heightData[x][y];
                    if(heightCodeData[x][y] == 10000) {
                        heightCodeData[x][y] = 1000;
                        continue;
                    }
                    else
                        heightCodeData[x][y] = (t = codeHeight(h));
                    hMod = 1.0;
                    switch (t) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            h = 0.4;
                            hMod = 0.2;
                            break;
                        case 6:
                            h = -0.1 * (h - forestLower - 0.08);
                            break;
                        case 7:
                            h *= -0.25;
                            break;
                        case 8:
                            h *= -0.4;
                            break;
                        default:
                            h *= 0.05;
                    }
                    heatData[x][y] = (h = (((heatData[x][y] - minHeat0) * heatDiff * hMod) + h + 0.6) * temp);
                    if (fresh) {
                        ps = Math.min(ps, h); //minHeat0
                        pc = Math.max(pc, h); //maxHeat0
                    }
                }
            }
            if(fresh)
            {
                minHeat1 = ps;
                maxHeat1 = pc;
            }
            heatDiff = heatModifier / (maxHeat1 - minHeat1);
            qs = Double.POSITIVE_INFINITY;
            qc = Double.NEGATIVE_INFINITY;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;


            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat1) * heatDiff));
                    moistureData[x][y] = (temp = (moistureData[x][y] - minWet0) * wetDiff);
                    if (fresh) {
                        qs = Math.min(qs, h);
                        qc = Math.max(qc, h);
                        ps = Math.min(ps, temp);
                        pc = Math.max(pc, temp);
                    }
                }
            }
            if(fresh)
            {
                minHeat = qs;
                maxHeat = qc;
                minWet = ps;
                maxWet = pc;
            }
            
        }
    }

    /**
     * A concrete implementation of {@link WorldMapGenerator} that projects the world map onto an ellipse that should be
     * twice as wide as it is tall (although you can stretch it by width and height that don't have that ratio).
     * This uses the <a href="https://en.wikipedia.org/wiki/Hammer_projection">Hammer projection</a>, so the latitude
     * lines are curved instead of flat. The Mollweide projection that {@link EllipticalMap} uses has flat lines, but
     * the two projection are otherwise very similar, and are both equal-area (Hammer tends to have less significant
     * distortion around the edges, but the curvature of the latitude lines can be hard to visualize).
     * <a href="https://i.imgur.com/nmN6lMK.gifv">Preview image link of a world rotating</a>.
     */
    public static class EllipticalHammerMap extends WorldMapGenerator {
        protected double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final Noise terrain, heat, moisture, otherRidged, terrainLayered;
        public final double[][] xPositions,
                yPositions,
                zPositions;
        protected final int[] edges;


        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape. This is very
         * similar to {@link EllipticalMap}, but has curved latitude lines instead of flat ones (it also may see more
         * internal usage because some operations on this projection are much faster and simpler).
         * Always makes a 200x100 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link EllipticalHammerMap#EllipticalHammerMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 200, 100, DEFAULT_NOISE, 1.0}.
         */
        public EllipticalHammerMap() {
            this(0x1337BABE1337D00DL, 200, 100, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape. This is very
         * similar to {@link EllipticalMap}, but has curved latitude lines instead of flat ones (it also may see more
         * internal usage because some operations on this projection are much faster and simpler).
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public EllipticalHammerMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight,  DEFAULT_NOISE,1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape. This is very
         * similar to {@link EllipticalMap}, but has curved latitude lines instead of flat ones (it also may see more
         * internal usage because some operations on this projection are much faster and simpler).
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public EllipticalHammerMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape. This is very
         * similar to {@link EllipticalMap}, but has curved latitude lines instead of flat ones (it also may see more
         * internal usage because some operations on this projection are much faster and simpler).
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public EllipticalHammerMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape. This is very
         * similar to {@link EllipticalMap}, but has curved latitude lines instead of flat ones (it also may see more
         * internal usage because some operations on this projection are much faster and simpler).
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail. The suggested Noise
         * implementation to use is {@link Noise#instance}.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public EllipticalHammerMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to display a projection of a globe onto an
         * ellipse without distortion of the sizes of features but with significant distortion of shape. This is very
         * similar to {@link EllipticalMap}, but has curved latitude lines instead of flat ones (it also may see more
         * internal usage because some operations on this projection are much faster and simpler).
         * Takes an initial seed, the width/height of the map, and parameters for noise generation (a
         * {@link Noise} implementation, where {@link Noise#instance} is suggested, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public EllipticalHammerMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier) {
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[width][height];
            yPositions = new double[width][height];
            zPositions = new double[width][height];
            edges = new int[height << 1];
            terrain = new Noise(seedA, (float) terrainFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 7));
            terrain.setFractalType(Noise.RIDGED_MULTI);
            otherRidged = new Noise(seedB, (float) otherFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 6));
            otherRidged.setFractalType(Noise.RIDGED_MULTI);
            terrainLayered = new Noise(seedB, (float) terrainRidgedFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 5));
            heat = new Noise(seedB, (float) heatFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 3), 0.75f, 1.333f);
            moisture = new Noise(seedB, (float) moistureFreq, Noise.SIMPLEX_FRACTAL, (int) (1 + octaveMultiplier * 4), 0.55f, 1f / 0.55f);
        }

        /**
         * Copies the EllipticalHammerMap {@code other} to construct a new one that is exactly the same. References will only
         * be shared to Noise classes.
         * @param other an EllipticalHammerMap to copy
         */
        public EllipticalHammerMap(EllipticalHammerMap other)
        {
            super(other);
            terrain = other.terrain;
            terrainLayered = other.terrainLayered;
            heat = other.heat;
            moisture = other.moisture;
            otherRidged = other.otherRidged;
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
            edges = Arrays.copyOf(other.edges, other.edges.length);
        }

        @Override
        public int wrapX(final int x, int y) {
            y = Math.max(0, Math.min(y, height - 1));
            if(x < edges[y << 1])
                return edges[y << 1 | 1];
            else if(x > edges[y << 1 | 1])
                return edges[y << 1];
            else return x;
        }

        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }

        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            boolean fresh = false;
            if(cacheA != stateA || cacheB != stateB || landMod != landModifier || heatMod != heatModifier)
            {
                minHeight = Double.POSITIVE_INFINITY;
                maxHeight = Double.NEGATIVE_INFINITY;
                minHeightActual = Double.POSITIVE_INFINITY;
                maxHeightActual = Double.NEGATIVE_INFINITY;
                minHeat0 = Double.POSITIVE_INFINITY;
                maxHeat0 = Double.NEGATIVE_INFINITY;
                minHeat1 = Double.POSITIVE_INFINITY;
                maxHeat1 = Double.NEGATIVE_INFINITY;
                minHeat = Double.POSITIVE_INFINITY;
                maxHeat = Double.NEGATIVE_INFINITY;
                minWet0 = Double.POSITIVE_INFINITY;
                maxWet0 = Double.NEGATIVE_INFINITY;
                minWet = Double.POSITIVE_INFINITY;
                maxWet = Double.NEGATIVE_INFINITY;
                cacheA = stateA;
                cacheB = stateB;
                fresh = true;
            }
            rng.setState(stateA, stateB);
            long seedA = rng.nextLong(), seedB = rng.nextLong(), seedC = rng.nextLong();
            int t;

            landModifier = (landMod <= 0) ? rng.nextDouble(0.2) + 0.91 : landMod;
            heatModifier = (heatMod <= 0) ? rng.nextDouble(0.45) * (rng.nextDouble()-0.5) + 1.1 : heatMod;

            double p,
                    ps, pc,
                    qs, qc,
                    h, temp, yPos, xPos,
                    z, th, lon, lat,
                    rx = width * 0.5, hw = width * 0.5, root2 = Math.sqrt(2.0),
                    irx = 1.0 / rx, iry = 2.0 / (double) height,
                    xAdj, yAdj,
                    i_uw = usedWidth / (double)(width),
                    i_uh = usedHeight / (double)(height);

            yPos = (startY - height * 0.5);
            for (int y = 0; y < height; y++, yPos += i_uh) {
                boolean inSpace = true;
                yAdj = yPos * iry;
                xPos = (startX - hw);
                for (int x = 0; x < width; x++, xPos += i_uw) {
                    xAdj = xPos * irx;
                    z = Math.sqrt(1.0 - 0.5 * xAdj * xAdj - 0.5 * yAdj * yAdj);
                    th = z * yAdj * root2;
                    lon = 2.0 * TrigTools.atan2((2.0 * z * z - 1.0), (z * xAdj * root2));
                    if(th != th || lon < 0.0) {
                        heightCodeData[x][y] = 10000;
                        inSpace = true;
                        continue;
                    }
                    lat = TrigTools.asin(th);
                    qc = TrigTools.cos(lat);
                    qs = th;
                    th = Math.PI - lon + centerLongitude;
                    if(inSpace)
                    {
                        inSpace = false;
                        edges[y << 1] = x;
                    }
                    edges[y << 1 | 1] = x;
                    ps = TrigTools.sin(th) * qc;
                    pc = TrigTools.cos(th) * qc;
                    xPositions[x][y] = pc;
                    yPositions[x][y] = ps;
                    zPositions[x][y] = qs;
                    heightData[x][y] = (h = terrainLayered.getNoiseWithSeed(pc +
                                    terrain.getNoiseWithSeed(pc, ps, qs,seedB - seedA) * 0.5,
                            ps, qs, seedA) + landModifier - 1.0);
                    heatData[x][y] = (p = heat.getNoiseWithSeed(pc, ps
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs,seedB + seedC)
                            , qs, seedB));
                    moistureData[x][y] = (temp = moisture.getNoiseWithSeed(pc, ps, qs
                                    + otherRidged.getNoiseWithSeed(pc, ps, qs, seedC + seedA)
                            , seedC));
                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                    if(fresh) {
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);

                        minHeat0 = Math.min(minHeat0, p);
                        maxHeat0 = Math.max(maxHeat0, p);

                        minWet0 = Math.min(minWet0, temp);
                        maxWet0 = Math.max(maxWet0, temp);
                    }
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);

            }
            double  heatDiff = 0.8 / (maxHeat0 - minHeat0),
                    wetDiff = 1.0 / (maxWet0 - minWet0),
                    hMod,
                    halfHeight = (height - 1) * 0.5, i_half = 1.0 / halfHeight;
            yPos = startY + i_uh;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < height; y++, yPos += i_uh) {
                temp = Math.abs(yPos - halfHeight) * i_half;
                temp *= (2.4 - temp);
                temp = 2.2 - temp;
                for (int x = 0; x < width; x++) {
                    h = heightData[x][y];
                    if(heightCodeData[x][y] == 10000) {
                        heightCodeData[x][y] = 1000;
                        continue;
                    }
                    else
                        heightCodeData[x][y] = (t = codeHeight(h));
                    hMod = 1.0;
                    switch (t) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            h = 0.4;
                            hMod = 0.2;
                            break;
                        case 6:
                            h = -0.1 * (h - forestLower - 0.08);
                            break;
                        case 7:
                            h *= -0.25;
                            break;
                        case 8:
                            h *= -0.4;
                            break;
                        default:
                            h *= 0.05;
                    }
                    heatData[x][y] = (h = (((heatData[x][y] - minHeat0) * heatDiff * hMod) + h + 0.6) * temp);
                    if (fresh) {
                        ps = Math.min(ps, h); //minHeat0
                        pc = Math.max(pc, h); //maxHeat0
                    }
                }
            }
            if(fresh)
            {
                minHeat1 = ps;
                maxHeat1 = pc;
            }
            heatDiff = heatModifier / (maxHeat1 - minHeat1);
            qs = Double.POSITIVE_INFINITY;
            qc = Double.NEGATIVE_INFINITY;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;


            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat1) * heatDiff));
                    moistureData[x][y] = (temp = (moistureData[x][y] - minWet0) * wetDiff);
                    if (fresh) {
                        qs = Math.min(qs, h);
                        qc = Math.max(qc, h);
                        ps = Math.min(ps, temp);
                        pc = Math.max(pc, temp);
                    }
                }
            }
            if(fresh)
            {
                minHeat = qs;
                maxHeat = qc;
                minWet = ps;
                maxWet = pc;
            }
            
        }
    }



    /**
     * A concrete implementation of {@link WorldMapGenerator} that imitates an infinite-distance perspective view of a
     * world, showing only one hemisphere, that should be as wide as it is tall (its outline is a circle). It should
     * look as a world would when viewed from space, and implements rotation differently to allow the planet to be
     * rotated without recalculating all the data, though it cannot zoom. Note that calling
     * {@link #setCenterLongitude(double)} does a lot more work than in other classes, but less than fully calling
     * {@link #generate()} in those classes, since it doesn't remake the map data at a slightly different rotation and
     * instead keeps a single map in use the whole time, using sections of it. This uses an
     * <a href="https://en.wikipedia.org/wiki/Orthographic_projection_in_cartography">Orthographic projection</a> with
     * the latitude always at the equator; the internal map is stored as a {@link SphereMap}, which uses a
     * <a href="https://en.wikipedia.org/wiki/Cylindrical_equal-area_projection#Discussion">cylindrical equal-area
     * projection</a>, specifically the Smyth equal-surface projection.
     * <br>
     * <a href="https://i.imgur.com/WNa5nQ1.gifv">Example view of a planet rotating</a>.
     * <a href="https://i.imgur.com/NV5IMd6.gifv">Another example</a>.
     */
    public static class RotatingSpaceMap extends WorldMapGenerator {
        protected double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final double[][] xPositions,
                yPositions,
                zPositions;
        protected final int[] edges;
        public final SphereMap storedMap;
        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Always makes a 100x100 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link RotatingSpaceMap#RotatingSpaceMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 100, 100, DEFAULT_NOISE, 1.0}.
         */
        public RotatingSpaceMap() {
            this(0x1337BABE1337D00DL, 100, 100, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public RotatingSpaceMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public RotatingSpaceMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public RotatingSpaceMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public RotatingSpaceMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to view a spherical world from space,
         * showing only one hemisphere at a time.
         * Takes an initial seed, the width/height of the map, and parameters for noise
         * generation (a {@link Noise} implementation, which is usually {@link Noise#instance}, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public RotatingSpaceMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier) {
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[mapWidth][mapHeight];
            yPositions = new double[mapWidth][mapHeight];
            zPositions = new double[mapWidth][mapHeight];
            edges = new int[height << 1];
            storedMap = new SphereMap(initialSeed, mapWidth << 1, mapHeight, noiseGenerator, octaveMultiplier);
        }

        /**
         * Copies the RotatingSpaceMap {@code other} to construct a new one that is exactly the same. References will only
         * be shared to Noise classes.
         * @param other a RotatingSpaceMap to copy
         */
        public RotatingSpaceMap(RotatingSpaceMap other)
        {
            super(other);
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
            edges = Arrays.copyOf(other.edges, other.edges.length);
            storedMap = new SphereMap(other.storedMap);
        }


        @Override
        public int wrapX(int x, int y) {
            y = Math.max(0, Math.min(y, height - 1));
            return Math.max(edges[y << 1], Math.min(x, edges[y << 1 | 1]));
        }

        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }

        @Override
        public void setCenterLongitude(double centerLongitude) {
            super.setCenterLongitude(centerLongitude);
            int ax, ay;
            double
                    ps, pc,
                    qs, qc,
                    h, yPos, xPos, iyPos, ixPos,
                    i_uw = usedWidth / (double)width,
                    i_uh = usedHeight / (double)height,
                    th, lon, lat, rho,
                    i_pi = 1.0 / Math.PI,
                    rx = width * 0.5, irx = i_uw / rx,
                    ry = height * 0.5, iry = i_uh / ry;

            yPos = startY - ry;
            iyPos = yPos / ry;
            for (int y = 0; y < height; y++, yPos += i_uh, iyPos += iry) {
                boolean inSpace = true;
                xPos = startX - rx;
                ixPos = xPos / rx;
                lat = TrigTools.asin(iyPos);
                for (int x = 0; x < width; x++, xPos += i_uw, ixPos += irx) {
                    rho = (ixPos * ixPos + iyPos * iyPos);
                    if(rho > 1.0) {
                        heightCodeData[x][y] = 1000;
                        inSpace = true;
                        continue;
                    }
                    rho = Math.sqrt(rho);
                    if(inSpace)
                    {
                        inSpace = false;
                        edges[y << 1] = x;
                    }
                    edges[y << 1 | 1] = x;
                    th = TrigTools.asin(rho); // c
                    lon = removeExcess((centerLongitude + (TrigTools.atan2(ixPos * rho, rho * TrigTools.cos(th)))) * 0.5);

                    qs = lat * 0.6366197723675814;
                    qc = qs + 1.0;
                    int sf = (qs >= 0.0 ? (int) qs : (int) qs - 1) & -2;
                    int cf = (qc >= 0.0 ? (int) qc : (int) qc - 1) & -2;
                    qs -= sf;
                    qc -= cf;
                    qs *= 2.0 - qs;
                    qc *= 2.0 - qc;
                    qs = qs * (-0.775 - 0.225 * qs) * ((sf & 2) - 1);
                    qc = qc * (-0.775 - 0.225 * qc) * ((cf & 2) - 1);


                    ps = lon * 0.6366197723675814;
                    pc = ps + 1.0;
                    sf = (ps >= 0.0 ? (int) ps : (int) ps - 1) & -2;
                    cf = (pc >= 0.0 ? (int) pc : (int) pc - 1) & -2;
                    ps -= sf;
                    pc -= cf;
                    ps *= 2.0 - ps;
                    pc *= 2.0 - pc;
                    ps = ps * (-0.775 - 0.225 * ps) * ((sf & 2) - 1);
                    pc = pc * (-0.775 - 0.225 * pc) * ((cf & 2) - 1);

                    ax = (int)((lon * i_pi + 1.0) * width);
                    ay = (int)((qs + 1.0) * ry);
                    
//                    // Hammer projection, not an inverse projection like we usually use
//                    z = 1.0 / Math.sqrt(1 + qc * TrigTools.cos(lon * 0.5));
//                    ax = (int)((qc * TrigTools.sin(lon * 0.5) * z + 1.0) * width);
//                    ay = (int)((qs * z + 1.0) * height * 0.5);

                    if(ax >= storedMap.width || ax < 0 || ay >= storedMap.height || ay < 0)
                    {
                        heightCodeData[x][y] = 1000;
                        continue;
                    }
                    if(storedMap.heightCodeData[ax][ay] >= 1000) // for the seam we get when looping around
                    {
                        ay = storedMap.wrapY(ax, ay);
                        ax = storedMap.wrapX(ax, ay);
                    }

                    xPositions[x][y] = pc * qc;
                    yPositions[x][y] = ps * qc;
                    zPositions[x][y] = qs;

                    heightData[x][y] = h = storedMap.heightData[ax][ay];
                    heightCodeData[x][y] = codeHeight(h);
                    heatData[x][y] = storedMap.heatData[ax][ay];
                    moistureData[x][y] = storedMap.moistureData[ax][ay];

                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);
            }

        }

        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            if(cacheA != stateA || cacheB != stateB)// || landMod != storedMap.landModifier || coolMod != storedMap.coolingModifier)
            {
                storedMap.regenerate(0, 0, width << 1, height, landMod, heatMod, stateA, stateB);
                minHeightActual = Double.POSITIVE_INFINITY;
                maxHeightActual = Double.NEGATIVE_INFINITY;

                minHeight = storedMap.minHeight;
                maxHeight = storedMap.maxHeight;

                minHeat0 = storedMap.minHeat0;
                maxHeat0 = storedMap.maxHeat0;

                minHeat1 = storedMap.minHeat1;
                maxHeat1 = storedMap.maxHeat1;

                minWet0 = storedMap.minWet0;
                maxWet0 = storedMap.maxWet0;

                minHeat = storedMap.minHeat;
                maxHeat = storedMap.maxHeat;

                minWet = storedMap.minWet;
                maxWet = storedMap.maxWet;

                cacheA = stateA;
                cacheB = stateB;
            }
            setCenterLongitude(centerLongitude);
            
        }
    }
    /**
     * A concrete implementation of {@link WorldMapGenerator} that does no projection of the map, as if the area were
     * completely flat or small enough that curvature is impossible to see. This also does not change heat levels at the
     * far north and south regions of the map, since it is meant for areas that are all about the same heat level.
     * <a href="http://squidpony.github.io/SquidLib/LocalMap.png" >Example map, showing lack of polar ice</a>
     */
    public static class LocalMap extends WorldMapGenerator {
        protected double minHeat0 = Double.POSITIVE_INFINITY, maxHeat0 = Double.NEGATIVE_INFINITY,
                minHeat1 = Double.POSITIVE_INFINITY, maxHeat1 = Double.NEGATIVE_INFINITY,
                minWet0 = Double.POSITIVE_INFINITY, maxWet0 = Double.NEGATIVE_INFINITY;

        public final Noise terrain, otherRidged;
        public final Noise heat, moisture, terrainLayered;
        public final double[][] xPositions,
                yPositions,
                zPositions;


        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Always makes a 256x128 map.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         * If you were using {@link LocalMap#LocalMap(long, int, int, Noise, double)}, then this would be the
         * same as passing the parameters {@code 0x1337BABE1337D00DL, 256, 128, DEFAULT_NOISE, 1.0}.
         */
        public LocalMap() {
            this(0x1337BABE1337D00DL, 256, 128, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes only the width/height of the map. The initial seed is set to the same large long
         * every time, and it's likely that you would set the seed when you call {@link #generate(long)}. The width and
         * height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param mapWidth  the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         */
        public LocalMap(int mapWidth, int mapHeight) {
            this(0x1337BABE1337D00DL, mapWidth, mapHeight,  DEFAULT_NOISE,1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         */
        public LocalMap(long initialSeed, int mapWidth, int mapHeight) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses Noise as its noise generator, with the given octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public LocalMap(long initialSeed, int mapWidth, int mapHeight, double octaveMultiplier) {
            this(initialSeed, mapWidth, mapHeight, DEFAULT_NOISE, octaveMultiplier);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed and the width/height of the map. The {@code initialSeed}
         * parameter may or may not be used, since you can specify the seed to use when you call {@link #generate(long)}.
         * The width and height of the map cannot be changed after the fact, but you can zoom in.
         * Uses the given noise generator, with 1.0 as the octave multiplier affecting detail.
         *
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth    the width of the map(s) to generate; cannot be changed later
         * @param mapHeight   the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise}
         */
        public LocalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator) {
            this(initialSeed, mapWidth, mapHeight, noiseGenerator, 1.0);
        }

        /**
         * Constructs a concrete WorldMapGenerator for a map that can be used to wrap a sphere (as with a texture on a
         * 3D model), with seamless east-west wrapping, no north-south wrapping, and distortion that causes the poles to
         * have significantly-exaggerated-in-size features while the equator is not distorted.
         * Takes an initial seed, the width/height of the map, and parameters for noise
         * generation (a {@link Noise} implementation, which is usually {@link Noise#instance}, and a
         * multiplier on how many octaves of noise to use, with 1.0 being normal (high) detail and higher multipliers
         * producing even more detailed noise when zoomed-in). The {@code initialSeed} parameter may or may not be used,
         * since you can specify the seed to use when you call {@link #generate(long)}. The width and height of the map
         * cannot be changed after the fact, but you can zoom in. Noise will be the fastest 3D generator to use for
         * {@code noiseGenerator}, and the seed it's constructed with doesn't matter because this will change the
         * seed several times at different scales of noise (it's fine to use the static {@link Noise#instance}
         * because it has no changing state between runs of the program). The {@code octaveMultiplier} parameter should
         * probably be no lower than 0.5, but can be arbitrarily high if you're willing to spend much more time on
         * generating detail only noticeable at very high zoom; normally 1.0 is fine and may even be too high for maps
         * that don't require zooming.
         * @param initialSeed the seed for the SilkRNG this uses; this may also be set per-call to generate
         * @param mapWidth the width of the map(s) to generate; cannot be changed later
         * @param mapHeight the height of the map(s) to generate; cannot be changed later
         * @param noiseGenerator an instance of a noise generator capable of 3D noise, usually {@link Noise#instance}
         * @param octaveMultiplier used to adjust the level of detail, with 0.5 at the bare-minimum detail and 1.0 normal
         */
        public LocalMap(long initialSeed, int mapWidth, int mapHeight, Noise noiseGenerator, double octaveMultiplier) {
            super(initialSeed, mapWidth, mapHeight);
            xPositions = new double[width][height];
            yPositions = new double[width][height];
            zPositions = new double[width][height];

            terrain = new Noise(seedA, (float) terrainFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 7));
            terrain.setFractalType(Noise.RIDGED_MULTI);
            otherRidged = new Noise(seedB, (float) otherFreq, Noise.FOAM_FRACTAL, (int) (0.5 + octaveMultiplier * 6));
            otherRidged.setFractalType(Noise.RIDGED_MULTI);
            terrainLayered = new Noise(seedB, (float) terrainRidgedFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 5));
            heat = new Noise(seedB, (float) heatFreq, Noise.FOAM_FRACTAL, (int) (1 + octaveMultiplier * 3), 0.75f, 1.333f);
            moisture = new Noise(seedB, (float) moistureFreq, Noise.SIMPLEX_FRACTAL, (int) (1 + octaveMultiplier * 4), 0.55f, 1f / 0.55f);
        }

        /**
         * Copies the LocalMap {@code other} to construct a new one that is exactly the same. References will only
         * be shared to Noise classes.
         * @param other a LocalMap to copy
         */
        public LocalMap(LocalMap other)
        {
            super(other);
            terrain = other.terrain;
            terrainLayered = other.terrainLayered;
            heat = other.heat;
            moisture = other.moisture;
            otherRidged = other.otherRidged;
            minHeat0 = other.minHeat0;
            maxHeat0 = other.maxHeat0;
            minHeat1 = other.minHeat1;
            maxHeat1 = other.maxHeat1;
            minWet0 = other.minWet0;
            maxWet0 = other.maxWet0;
            xPositions = copy(other.xPositions);
            yPositions = copy(other.yPositions);
            zPositions = copy(other.zPositions);
        }

        @Override
        public int wrapX(final int x, final int y)  {
            return Math.max(0, Math.min(x, width - 1));
        }

        @Override
        public int wrapY(final int x, final int y)  {
            return Math.max(0, Math.min(y, height - 1));
        }
        
        protected void regenerate(int startX, int startY, int usedWidth, int usedHeight,
                                  double landMod, double heatMod, int stateA, int stateB)
        {
            boolean fresh = false;
            if(cacheA != stateA || cacheB != stateB || landMod != landModifier || heatMod != heatModifier)
            {
                minHeight = Double.POSITIVE_INFINITY;
                maxHeight = Double.NEGATIVE_INFINITY;
                minHeat0 = Double.POSITIVE_INFINITY;
                maxHeat0 = Double.NEGATIVE_INFINITY;
                minHeat1 = Double.POSITIVE_INFINITY;
                maxHeat1 = Double.NEGATIVE_INFINITY;
                minHeat = Double.POSITIVE_INFINITY;
                maxHeat = Double.NEGATIVE_INFINITY;
                minWet0 = Double.POSITIVE_INFINITY;
                maxWet0 = Double.NEGATIVE_INFINITY;
                minWet = Double.POSITIVE_INFINITY;
                maxWet = Double.NEGATIVE_INFINITY;
                cacheA = stateA;
                cacheB = stateB;
                fresh = true;
            }
            rng.setState(stateA, stateB);
            long seedA = rng.nextLong(), seedB = rng.nextLong(), seedC = rng.nextLong();
            int t;

            landModifier = (landMod <= 0) ? rng.nextDouble(0.29) + 0.91 : landMod;
            heatModifier = (heatMod <= 0) ? rng.nextDouble(0.45) * (rng.nextDouble()-0.5) + 1.1 : heatMod;

            double p,
                    ps, pc,
                    qs, qc,
                    h, temp,
                    i_w = 1.0 / width, i_h = 1.0 / (height),  ii = Math.max(i_w, i_h),
                    i_uw = usedWidth * i_w * ii, i_uh = usedHeight * i_h * ii, xPos, yPos = startY * i_h;
            for (int y = 0; y < height; y++, yPos += i_uh) { 
                xPos = startX * i_w;
                for (int x = 0; x < width; x++, xPos += i_uw) {
                    xPositions[x][y] = xPos;
                    yPositions[x][y] = yPos;
                    zPositions[x][y] = 0.0;
                    heightData[x][y] = (h = terrainLayered.getNoiseWithSeed(xPos +
                                    terrain.getNoiseWithSeed(xPos, yPos, seedB - seedA) * 0.5,
                            yPos, seedA) + landModifier - 1.0);
                    heatData[x][y] = (p = heat.getNoiseWithSeed(xPos, yPos
                                    + otherRidged.getNoiseWithSeed(xPos, yPos, seedB + seedC),
                            seedB));
                    temp = otherRidged.getNoiseWithSeed(xPos, yPos, seedC + seedA);
                    moistureData[x][y] = (temp = moisture.getNoiseWithSeed(xPos - temp, yPos + temp, seedC));

                    minHeightActual = Math.min(minHeightActual, h);
                    maxHeightActual = Math.max(maxHeightActual, h);
                    if(fresh) {
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);

                        minHeat0 = Math.min(minHeat0, p);
                        maxHeat0 = Math.max(maxHeat0, p);

                        minWet0 = Math.min(minWet0, temp);
                        maxWet0 = Math.max(maxWet0, temp);
                    }
                }
                minHeightActual = Math.min(minHeightActual, minHeight);
                maxHeightActual = Math.max(maxHeightActual, maxHeight);

            }
            double  heatDiff = 0.8 / (maxHeat0 - minHeat0),
                    wetDiff = 1.0 / (maxWet0 - minWet0),
                    hMod;
            yPos = startY * i_h + i_uh;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < height; y++, yPos += i_uh) {
                for (int x = 0; x < width; x++) {
                    h = heightData[x][y];
                    heightCodeData[x][y] = (t = codeHeight(h));
                    hMod = 1.0;
                    switch (t) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            h = 0.4;
                            hMod = 0.2;
                            break;
                        case 6:
                            h = -0.1 * (h - forestLower - 0.08);
                            break;
                        case 7:
                            h *= -0.25;
                            break;
                        case 8:
                            h *= -0.4;
                            break;
                        default:
                            h *= 0.05;
                    }
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat0) * heatDiff * hMod) + h + 0.6);
                    if (fresh) {
                        ps = Math.min(ps, h); //minHeat0
                        pc = Math.max(pc, h); //maxHeat0
                    }
                }
            }
            if(fresh)
            {
                minHeat1 = ps;
                maxHeat1 = pc;
            }
            heatDiff = heatModifier / (maxHeat1 - minHeat1);
            qs = Double.POSITIVE_INFINITY;
            qc = Double.NEGATIVE_INFINITY;
            ps = Double.POSITIVE_INFINITY;
            pc = Double.NEGATIVE_INFINITY;


            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    heatData[x][y] = (h = ((heatData[x][y] - minHeat1) * heatDiff));
                    moistureData[x][y] = (temp = (moistureData[x][y] - minWet0) * wetDiff);
                    if (fresh) {
                        qs = Math.min(qs, h);
                        qc = Math.max(qc, h);
                        ps = Math.min(ps, temp);
                        pc = Math.max(pc, temp);
                    }
                }
            }
            if(fresh)
            {
                minHeat = qs;
                maxHeat = qc;
                minWet = ps;
                maxWet = pc;
            }
            
        }
    }


    /**
     * Gets a copy of the 2D int array, source, that has the same data but shares no references with source.
     *
     * @param source a 2D int array
     * @return a copy of source, or null if source is null
     */
    public static int[][] copy(int[][] source) {
        if (source == null)
            return null;
        if (source.length < 1)
            return new int[0][0];
        int[][] target = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            target[i] = new int[source[i].length];
            System.arraycopy(source[i], 0, target[i], 0, source[i].length);
        }
        return target;
    }

    /**
     * Gets a copy of the 2D double array, source, that has the same data but shares no references with source.
     *
     * @param source a 2D double array
     * @return a copy of source, or null if source is null
     */
    public static double[][] copy(double[][] source) {
        if (source == null)
            return null;
        if (source.length < 1)
            return new double[0][0];
        double[][] target = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            target[i] = new double[source[i].length];
            System.arraycopy(source[i], 0, target[i], 0, source[i].length);
        }
        return target;
    }

}
