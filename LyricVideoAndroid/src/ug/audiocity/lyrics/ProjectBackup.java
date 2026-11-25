package ug.audiocity.lyrics;

import java.io.*;
import java.util.HashMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * One-file backup of every project (songs, lyrics, timings, photos, videos, fonts, styles) as a ZIP, and restoring it.
 * Restored projects always get new folders, so nothing already on the phone is overwritten. Plain Java, tested.
 */
final class ProjectBackup {
    static final String MARK = "lyric-video-studio-backup.txt";
    static final long MAX_TOTAL = 8L * 1024 * 1024 * 1024; // safety limit when reading

    /** Writes all project folders under root; returns how many projects were saved. */
    static int write(File root, OutputStream target) throws IOException {
        int n = 0;
        File[] dirs = root.listFiles(File::isDirectory);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target))) {
            zip.putNextEntry(new ZipEntry(MARK));
            zip.write("Lyric Video Studio projects backup, format 1\n".getBytes("UTF-8"));
            zip.closeEntry();
            byte[] buf = new byte[1 << 16];
            if (dirs != null) for (File d : dirs) {
                if (!new File(d, "project.json").isFile()) continue;
                File[] files = d.listFiles(File::isFile);
                if (files == null) continue;
                for (File f : files) {
                    String name = f.getName();
                    if (name.endsWith(".tmp") || name.endsWith(".part") || name.startsWith("incoming")) continue;
                    ZipEntry e = new ZipEntry(d.getName() + "/" + name);
                    // Songs, photos and videos are already compressed: store them as they are (much faster).
                    if (!name.endsWith(".json") && !name.endsWith(".bin")) { e.setMethod(ZipEntry.STORED); e.setSize(f.length()); e.setCrc(crc(f)); }
                    zip.putNextEntry(e);
                    try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { int k; while ((k = in.read(buf)) != -1) zip.write(buf, 0, k); }
                    zip.closeEntry();
                }
                n++;
            }
        }
        return n;
    }

    static long crc(File f) throws IOException {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { int k; while ((k = in.read(buf)) != -1) c.update(buf, 0, k); }
        return c.getValue();
    }

    /** Restores projects into new folders under root; returns how many projects were restored. */
    static int read(InputStream source, File root) throws IOException {
        HashMap<String, File> folders = new HashMap<>();
        boolean marked = false;
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals(MARK)) { marked = true; continue; }
                if (e.isDirectory()) continue;
                String[] parts = name.split("/");
                // Only "<project>/<file>": no paths that climb out of the projects folder.
                if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty() || parts[0].contains("..") || parts[1].contains("..") || parts[1].contains("\\\\")) continue;
                File dir = folders.get(parts[0]);
                if (dir == null) {
                    dir = new File(root, UUID.randomUUID().toString());
                    if (!dir.mkdirs()) throw new IOException("Cannot create project storage.");
                    folders.put(parts[0], dir);
                }
                File out = new File(dir, parts[1]);
                if (!out.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) continue;
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
                    int k;
                    while ((k = zip.read(buf)) != -1) {
                        total += k;
                        if (total > MAX_TOTAL) throw new IOException("The backup is larger than expected.");
                        o.write(buf, 0, k);
                    }
                }
            }
        }
        int n = 0;
        for (File d : folders.values()) {
            if (new File(d, "project.json").isFile()) n++;
            else { File[] f = d.listFiles(); if (f != null) for (File x : f) x.delete(); d.delete(); }
        }
        if (!marked && n == 0) throw new IOException("That file is not a Lyric Video Studio backup.");
        return n;
    }
}
