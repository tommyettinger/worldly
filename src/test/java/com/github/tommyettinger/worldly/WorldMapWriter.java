package com.github.tommyettinger.worldly;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.NumberUtils;
import com.badlogic.gdx.utils.viewport.StretchViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

/**
 * Port of Zachary Carter's world generation technique, https://github.com/zacharycarter/mapgen
 * It seems to mostly work now, though it only generates one view of the map that it renders (but biome, moisture, heat,
 * and height maps can all be requested from it).
 */
public class WorldMapWriter extends ApplicationAdapter {
//    private static final int width = 1920, height = 1080;
//    private static final int width = 256, height = 256; // localMimic
//    private static final int width = 512, height = 256; // mimic, elliptical
//    private static final int width = 1024, height = 512; // mimic, elliptical
//    private static final int width = 2048, height = 1024; // mimic, elliptical
    private static final int width = 800, height = 800; // space view
//    private static final int width = 600, height = 600; // space view
//    private static final int width = 1200, height = 400; // squat
    private static final int LIMIT = 25;
    //private static final int width = 256, height = 128;
    //private static final int width = 314 * 4, height = 400;
    //private static final int width = 512, height = 512;

    private SpriteBatch batch;

    private StringBuilder sb = new StringBuilder(64);
    private String makeName()
    {
        sb.setLength(0);
        for (int i = rng.nextInt(7) + 4; i >= 0; i--) {
            sb.append((char)(rng.between('a', 'z'+1)));
        }
        return sb.toString();
    }

    private Pixmap pm;
    private Texture pt;
    private int counter = 0;
    private static final int cellWidth = 1, cellHeight = 1;
    private float[] colors = new float[15];
    private Viewport view;
    private SilkRNG rng;
    private long seed;
    private long ttg = 0; // time to generate
    private WorldMapGenerator world;
    private WorldMapView wmv;
    private PixmapIO.PNG writer;
    
    private String date, path;

    @Override
    public void create() {
        batch = new SpriteBatch();
        view = new StretchViewport(width * cellWidth, height * cellHeight);
        date = DateFormat.getDateInstance().format(new Date());
        path = "samples/clear/" + width + "x" + height + "/";
        
        Gdx.files.local(path).mkdirs();
        
        pm = new Pixmap(width * cellWidth, height * cellHeight, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.SourceOver);
        pt = new Texture(pm);

        writer = new PixmapIO.PNG((int)(pm.getWidth() * pm.getHeight() * 1.5f)); // Guess at deflated size.
        writer.setFlipY(false);
        writer.setCompression(6);
        rng = new SilkRNG(date.hashCode() + Gdx.files.local(path).list().length);
        seed = rng.getState();

        world = new WorldMapGenerator.SpaceViewMap(seed, width, height, WorldMapGenerator.DEFAULT_NOISE, 0.9);
        wmv = new WorldMapView(world);

        //generate(seed);
        rng.setState(seed);
    }

    public void generate(final long seed)
    {
        long startTime = System.currentTimeMillis();
        //randomizeColors(seed);
//        world.generate(1, 1.125, seed); // mimic of Earth favors too-cold planets
//        dbm.makeBiomes(world);
//        world = new WorldMapGenerator.SpaceViewMap(seed, width, height, FastNoise.instance, octaveCounter * 0.001);
//        world = new WorldMapGenerator.HyperellipticalMap(seed, width, height, ClassicNoise.instance, octaveCounter * 0.001, 0.0625, 2.5);
//        world = new WorldMapGenerator.EllipticalMap(seed, width, height, ClassicNoise.instance, octaveCounter * 0.001);
//        world.generate(0.95 + NumberTools.formCurvedDouble((seed ^ 0x123456789ABCDL) * 0x12345689ABL) * 0.15,
//                DiverRNG.determineDouble(seed * 0x12345L + 0x54321L) * 0.2 + 1.0, seed);
//        dbm.makeBiomes(world);
        world.rng.setState(seed);
        world.seedA = world.rng.stateA;
        world.seedB = world.rng.stateB;
        colors[0] = Color.toFloatBits(0.75f + rng.nextFloat(0.05f), 0.6f + rng.nextFloat(0.04f), 0.38f + rng.nextFloat(0.03f), 1f);
        for (int i = 1; i < colors.length; i++) {
            colors[i] = WorldMapView.toEditedFloat(colors[i-1], rng.nextFloat(0.03f) - 0.015f, rng.nextFloat(0.16f) - 0.08f, rng.nextFloat(0.2f) - 0.1f, 0f);
        }
        wmv.match(colors);
        wmv.generate((int)(seed & 0xFFFFFFFFL), (int) (seed >>> 32),
                1.5 + WorldMapView.formCurvedDouble((seed ^ 0x123456789ABCDL) * 0x12345689ABL) * 0.3,
                1.6 + WorldMapView.formCurvedDouble((seed ^ 0xFEDCBA987654321L) * 0xABCDEFABCDEFABCDL) * 0.3);
        ttg = System.currentTimeMillis() - startTime;
    }

    public void putMap() {
        ++counter;
        String name = makeName();
        while (Gdx.files.local(path + name + ".png").exists())
            name = makeName();


        //// this is the main part you would want to copy if you want to generate Pixmaps
        generate(name.hashCode());
        float[][] cm = wmv.show();
//        pm.setColor(0x222034FF); // could also be black
        pm.setColor(0); // transparent
        pm.fill();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                // this just converts the SpriteBatch-usable ABGR floats to Pixmap-usable RGBA ints
                pm.drawPixel(x, y, Integer.reverseBytes(NumberUtils.floatToIntBits(cm[x][y])));
            }
        }

        batch.begin();
        pt.draw(pm, 0, 0);
        batch.draw(pt, 0, 0, width, height);
        batch.end();
        
        try {
            writer.write(Gdx.files.local(path + name + ".png"), pm); // , false);
        } catch (IOException ex) {
            throw new GdxRuntimeException("Error writing PNG: " + path + name + ".png", ex);
        }

        if(counter >= LIMIT)
                Gdx.app.exit();
    }
    @Override
    public void render() {
        // standard clear the background routine for libGDX
        //Gdx.gl.glClearColor(0f, 0f, 0f, 1.0f);
        //Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        // need to display the map every frame, since we clear the screen to avoid artifacts.
        putMap();
        Gdx.graphics.setTitle("Map! Took " + ttg + " ms to generate");
    }

    @Override
    public void dispose() {
        super.dispose();
        writer.dispose();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        view.update(width, height, true);
        view.apply(true);
    }

    public static void main(String[] arg) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("World Map Writer");
        config.setWindowedMode(width * cellWidth, height * cellHeight);
        config.setWindowIcon(Files.FileType.Internal, "Tentacle-128.png", "Tentacle-64.png", "Tentacle-32.png", "Tentacle-16.png");
        new Lwjgl3Application(new WorldMapWriter(), config);
    }
}
