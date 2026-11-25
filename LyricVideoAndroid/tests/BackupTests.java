package ug.audiocity.lyrics;

import java.io.*;
import java.nio.file.Files;

/** Desktop checks for the projects backup (write, restore into new folders, unsafe paths ignored). */
public final class BackupTests {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("lvs").toFile();
        File root = new File(base, "projects"); root.mkdirs();
        File a = new File(root, "aaa"); a.mkdirs();
        write(new File(a, "project.json"), "{\"title\":\"Comando\"}");
        byte[] song = new byte[300_000]; new java.util.Random(1).nextBytes(song);
        Files.write(new File(a, "song.mp3").toPath(), song);
        write(new File(a, "incoming-image"), "partial");
        File junk = new File(root, "not-a-project"); junk.mkdirs(); write(new File(junk, "x.txt"), "x");
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        check(ProjectBackup.write(root, zip) == 1, "one project backed up");
        File other = new File(base, "phone2"); other.mkdirs();
        check(ProjectBackup.read(new ByteArrayInputStream(zip.toByteArray()), other) == 1, "one project restored");
        File[] restored = other.listFiles(File::isDirectory);
        check(restored != null && restored.length == 1 && !restored[0].getName().equals("aaa"), "restored into a new folder (nothing overwritten)");
        check(java.util.Arrays.equals(Files.readAllBytes(new File(restored[0], "song.mp3").toPath()), song), "song restored byte for byte");
        check(new String(Files.readAllBytes(new File(restored[0], "project.json").toPath()), "UTF-8").contains("Comando"), "project restored");
        check(!new File(restored[0], "incoming-image").exists(), "half-finished imports are not backed up");
        check(ProjectBackup.read(new ByteArrayInputStream(zip.toByteArray()), other) == 1 && other.listFiles(File::isDirectory).length == 2, "restoring twice makes a second copy");
        // A crafted file must not write outside the projects folder.
        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(evil)) {
            z.putNextEntry(new java.util.zip.ZipEntry("../../escape.txt")); z.write(1); z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("p/../../x.json")); z.write(1); z.closeEntry();
        }
        File safe = new File(base, "safe"); safe.mkdirs();
        boolean refused = false;
        try { ProjectBackup.read(new ByteArrayInputStream(evil.toByteArray()), safe); } catch (IOException e) { refused = true; }
        check(refused && !new File(base, "escape.txt").exists() && !new File(base, "x.json").exists(), "unsafe paths ignored, not a backup");
        System.out.println("BackupTests: " + checks + " checks passed");
    }

    static void write(File f, String s) throws IOException { Files.write(f.toPath(), s.getBytes("UTF-8")); }
}
