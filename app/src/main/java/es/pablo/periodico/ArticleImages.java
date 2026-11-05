package es.pablo.periodico;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ArticleImages {
    private static final int MAX_BYTES=4*1024*1024;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>((int)Math.min(12*1024*1024,Runtime.getRuntime().maxMemory()/12)) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final ColorMatrixColorFilter GRAY;
    static { ColorMatrix matrix=new ColorMatrix(); matrix.setSaturation(0); GRAY=new ColorMatrixColorFilter(matrix); }
    private static final class Request { volatile boolean cancelled; }

    static void load(ImageView image, String value) {
        clear(image);
        Request request=new Request();
        image.setTag(request);
        image.setVisibility(View.INVISIBLE);
        image.setColorFilter(GRAY);
        String url=News.imageUrl(value);
        if (url.isEmpty()) { image.setVisibility(View.GONE); return; }
        Bitmap cached=CACHE.get(url);
        if (cached!=null) { image.setImageBitmap(cached); image.setVisibility(View.VISIBLE); return; }
        WeakReference<ImageView> target=new WeakReference<>(image);
        WORKERS.execute(()->{
            if (target.get()==null || request.cancelled) return;
            Bitmap bitmap=null;
            try { bitmap=download(url,request); } catch (Exception | OutOfMemoryError ignored) {}
            if (bitmap!=null && !request.cancelled) CACHE.put(url,bitmap);
            Bitmap loaded=bitmap;
            MAIN.post(()->{
                ImageView current=target.get();
                if (current==null || current.getTag()!=request || request.cancelled) return;
                if (loaded!=null) { current.setImageBitmap(loaded); current.setVisibility(View.VISIBLE); }
                else current.setVisibility(View.GONE);
            });
        });
    }
    static void clear(ImageView image) {
        Object request=image.getTag();
        if (request instanceof Request) ((Request)request).cancelled=true;
        image.setTag(null);
        image.setImageDrawable(null);
        if (image.getVisibility()!=View.GONE) image.setVisibility(View.INVISIBLE);
    }

    private static Bitmap download(String address, Request request) throws Exception {
        URL url=new URL(address);
        for (int redirects=0; redirects<=3; redirects++) {
            if (request.cancelled || News.imageUrl(url.toExternalForm()).isEmpty()) return null;
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Mozilla/5.0 Periodico/1.0");
            try {
                int status=connection.getResponseCode();
                if (status==301 || status==302 || status==303 || status==307 || status==308) {
                    String location=connection.getHeaderField("Location");
                    if (location==null) return null;
                    url=new URL(url,location);
                    continue;
                }
                String type=connection.getContentType();
                if (status!=200 || connection.getContentLengthLong()>MAX_BYTES || (type!=null && !type.toLowerCase(Locale.ROOT).startsWith("image/"))) return null;
                byte[] data;
                try (InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192]; int read;
                    while ((read=input.read(buffer))!=-1) {
                        if (request.cancelled || output.size()+read>MAX_BYTES) return null;
                        output.write(buffer,0,read);
                    }
                    data=output.toByteArray();
                }
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(data,0,data.length,options);
                if (request.cancelled || options.outWidth<=0 || options.outHeight<=0) return null;
                options.inSampleSize=1;
                while (options.outWidth/options.inSampleSize>1600 || options.outHeight/options.inSampleSize>1600 || (long)(options.outWidth/options.inSampleSize)*(options.outHeight/options.inSampleSize)>1200000) options.inSampleSize*=2;
                options.inJustDecodeBounds=false;
                options.inPreferredConfig=Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(data,0,data.length,options);
            } finally { connection.disconnect(); }
        }
        return null;
    }
}
