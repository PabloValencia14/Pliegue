package es.pablo.periodico;
import android.app.*;
import android.app.job.*;
import android.content.*;
import org.json.JSONArray;
public class DailyJob extends JobService {
 volatile boolean stopped;
 @Override public boolean onStartJob(JobParameters p){stopped=false;new Thread(()->{JSONArray a=News.fetch(this);if(stopped)return;if(a.length()>0){News.save(this,a);NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("daily","Edición diaria",NotificationManager.IMPORTANCE_DEFAULT));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);try{nm.notify(8,new Notification.Builder(this,"daily").setSmallIcon(R.drawable.pliegue_mark).setContentTitle(getString(R.string.app_name)).setContentText("La edición de hoy está lista").setContentIntent(open).setAutoCancel(true).build());}catch(SecurityException ignored){}}jobFinished(p,a.length()==0);}).start();return true;}
 @Override public boolean onStopJob(JobParameters p){stopped=true;return true;}
}
