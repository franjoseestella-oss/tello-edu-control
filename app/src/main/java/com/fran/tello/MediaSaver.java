package com.fran.tello;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Guarda fotos y vídeos en la galería (Pictures/Tello y Movies/Tello). */
public class MediaSaver {

    /** Guarda un Bitmap como JPG. Devuelve una descripción del destino o null si falla. */
    public static String saveImage(Context ctx, Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Tello");
                ContentResolver r = ctx.getContentResolver();
                Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (OutputStream os = r.openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                return "Galería/Tello";
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "Tello");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name + ".jpg");
                try (OutputStream os = new java.io.FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Copia un MP4 ya grabado a la galería (Movies/Tello). */
    public static String saveVideo(Context ctx, File src, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name + ".mp4");
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tello");
                ContentResolver r = ctx.getContentResolver();
                Uri uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = r.openOutputStream(uri)) {
                    copy(in, out);
                }
                src.delete();
                return "Galería/Tello";
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "Tello");
                if (!dir.exists()) dir.mkdirs();
                File dst = new File(dir, name + ".mp4");
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new java.io.FileOutputStream(dst)) {
                    copy(in, out);
                }
                src.delete();
                MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, null, null);
                return dst.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
