package es.pablo.periodico;
import android.app.*;
import android.app.job.*;
import android.content.*;
import java.util.Calendar;
public class DailyReceiver extends BroadcastReceiver {
 static void schedule(Context c){Calendar n=Calendar.getInstance();n.set(Calendar.HOUR_OF_DAY,8);n.set(Calendar.MINUTE,0);n.set(Calendar.SECOND,0);n.set(Calendar.MILLISECOND,0);if(n.getTimeInMillis()<=System.currentTimeMillis())n.add(Calendar.DATE,1);PendingIntent p=PendingIntent.getBroadcast(c,0,new Intent(c,DailyReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,n.getTimeInMillis(),p);}
 @Override public void onReceive(Context c,Intent i){schedule(c);if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()))return;JobInfo job=new JobInfo.Builder(8,new ComponentName(c,DailyJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(900000,JobInfo.BACKOFF_POLICY_LINEAR).build();((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(job);}
}
