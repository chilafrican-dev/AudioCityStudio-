package android.util;
import java.io.*;import java.nio.file.*;
/** Host-only adapter for session-format tests. Not included in the APK. */
public final class AtomicFile {private final File file;public AtomicFile(File file){this.file=file;}public FileOutputStream startWrite()throws IOException{return new FileOutputStream(file);}public void finishWrite(FileOutputStream f)throws IOException{f.close();}public void failWrite(FileOutputStream f)throws IOException{f.close();}public byte[] readFully()throws IOException{return Files.readAllBytes(file.toPath());}}
