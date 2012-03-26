package com.googlecode.iptableslog;

import android.app.TabActivity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.TabWidget;
import android.widget.TabHost;
import android.content.res.Resources;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.app.AlertDialog;
import android.content.DialogInterface;

public class IptablesLog extends TabActivity
{
  public static IptablesLogData data = null;

  public static LogView logView;
  public static AppView appView;
  
  public static IptablesLogTracker logTracker;
  public static Settings settings;

  public static Handler handler;
  
  public static Object scriptLock = new Object();

  public static CharSequence filterText;
  public static boolean filterUid;
  public static boolean filterName;
  public static boolean filterAddress;
  public static boolean filterPort;

  public static NetworkResolver resolver;
  public static boolean resolveHosts;
  public static boolean resolvePorts;
  
  public static State state;
  public enum State { LOAD_APPS, LOAD_LIST, LOAD_ICONS, RUNNING  };

  public static InitRunner initRunner;
  public class InitRunner implements Runnable
  {
    private Context context;
    public boolean running = false;

    public InitRunner(Context context) {
      this.context = context;
    }

    public void stop() {
      running = false;
    }

    public void run() {
      MyLog.d("Init begin");
      running = true;

      Looper.myLooper().prepare();

      state = IptablesLog.State.LOAD_APPS;
      ApplicationsTracker.getInstalledApps(context, handler);

      if(running == false)
        return;

      state = IptablesLog.State.LOAD_LIST;
      appView.getInstalledApps();

      if(running == false)
        return;

      state = IptablesLog.State.LOAD_ICONS;
      appView.loadIcons();

      logTracker = new IptablesLogTracker();

      appView.attachListener();
      appView.startUpdater();

      logView.attachListener();
      logView.startUpdater();

      logTracker.start(data != null);

      state = IptablesLog.State.RUNNING;
      MyLog.d("Init done");
    }
  }

  /** Called when the activity is first created. */
  @Override
    public void onCreate(Bundle savedInstanceState)
    {
      super.onCreate(savedInstanceState);
      MyLog.d("IptablesLog onCreate");

      settings = new Settings(this);

      MyLog.enabled = settings.getLogcatDebug();
      filterText = settings.getFilterText();
      filterUid = settings.getFilterUid();
      filterName = settings.getFilterName();
      filterAddress = settings.getFilterAddress();
      filterPort = settings.getFilterPort();
      resolveHosts = settings.getResolveHosts();
      resolvePorts = settings.getResolvePorts();

      data = (IptablesLogData) getLastNonConfigurationInstance(); 

      setContentView(R.layout.main);
      handler = new Handler(Looper.getMainLooper());

      if (data != null) {
        MyLog.d("Restored run");
        ApplicationsTracker.restoreData(data);
        resolver = data.iptablesLogResolver;
      } else {
        MyLog.d("Fresh run");
        resolver = new NetworkResolver();
      }

      Resources res = getResources();
      TabHost tabHost = getTabHost();
      TabHost.TabSpec spec;
      Intent intent;

      intent = new Intent().setClass(this, LogView.class);
      spec = tabHost.newTabSpec("log").setIndicator("Log", 
          res.getDrawable(R.drawable.tab_logview)).setContent(intent);
      tabHost.addTab(spec);

      intent = new Intent().setClass(this, AppView.class);
      spec = tabHost.newTabSpec("apps").setIndicator("Apps", 
          res.getDrawable(R.drawable.tab_appview)).setContent(intent);
      tabHost.addTab(spec);

      // todo: redesign tabs to be views instead of activities
      //       as this should be less complex and save resources

      // force loading of LogView activity
      tabHost.setCurrentTab(0);
      logView = (LogView) getLocalActivityManager().getCurrentActivity();
      // force loading of AppView activity
      tabHost.setCurrentTab(1);
      appView = (AppView) getLocalActivityManager().getCurrentActivity();

      // display LogView tab by default
      tabHost.setCurrentTab(0);

      if (data == null) {
        initRunner = new InitRunner(this);
        new Thread(initRunner, "Initialization " + initRunner).start();
      } else {
        state = data.iptablesLogState;

        if(state != IptablesLog.State.RUNNING) {
          initRunner = new InitRunner(this);
          new Thread(initRunner, "Initialization " + initRunner).start();
        } else {
          logTracker = new IptablesLogTracker();

          appView.attachListener();
          appView.startUpdater();

          logView.attachListener();
          logView.startUpdater();

          logTracker.start(true);
        }
        // all data should be restored at this point, release the object
        data = null;
        MyLog.d("data object released");

        state = IptablesLog.State.RUNNING;
      }
    }

  @Override
    public void onResume()
    {
      super.onResume();

      MyLog.d("onResume()");
    }

  @Override
    public void onPause()
    {
      super.onPause();

      MyLog.d("onPause()");
    }

  @Override
    public void onDestroy()
    {
      super.onDestroy();
      MyLog.d("onDestroy called");

      if(initRunner != null)
        initRunner.stop();
      
      if(logView != null)
        logView.stopUpdater();

      if(appView != null)
        appView.stopUpdater();

      if(logTracker != null)
        logTracker.stop();

      synchronized(ApplicationsTracker.dialogLock) {
        if(ApplicationsTracker.dialog != null) {
          ApplicationsTracker.dialog.dismiss();
          ApplicationsTracker.dialog = null;
        }
      }

      if(data == null) {
        MyLog.d("Shutting down rules");
        Iptables.removeRules();
        IptablesLog.logTracker.kill();
      } else {
        MyLog.d("Not shutting down rules");
      }
    }

  @Override
    public Object onRetainNonConfigurationInstance() {
      MyLog.d("Saving run");
      data = new IptablesLogData();
      return data;
    }


  @Override
    public boolean onCreateOptionsMenu(Menu menu) {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(R.layout.menu, menu);
      return true; 
    }

  @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
      MenuItem item;

      item = menu.findItem(R.id.sort);

      if(getLocalActivityManager().getCurrentActivity() instanceof AppView) {
        item.setVisible(true);

        switch(appView.sortBy) {
          case UID:
            item = menu.findItem(R.id.sort_by_uid);
            break;
          case NAME:
            item = menu.findItem(R.id.sort_by_name);
            break;
          case PACKETS:
            item = menu.findItem(R.id.sort_by_packets);
            break;
          case BYTES:
            item = menu.findItem(R.id.sort_by_bytes);
            break;
          case TIMESTAMP:
            item = menu.findItem(R.id.sort_by_timestamp);
            break;
          default:
            IptablesLog.settings.setSortBy(Sort.BYTES);
            appView.sortBy = Sort.BYTES;
            item = menu.findItem(R.id.sort_by_bytes);
        }

        item.setChecked(true);
      } else {
        item.setVisible(false);
      }

      return true;
    }

  @Override
    public boolean onOptionsItemSelected(MenuItem item) {
      switch(item.getItemId()) {
        case R.id.filter:
          showFilterDialog();
          break;
        case R.id.reset:
          confirmReset(this);
          break;
        case R.id.exit:
          confirmExit(this);
          break;
        case R.id.settings:
          startActivity(new Intent(this, Preferences.class));
          break;
        case R.id.sort_by_uid:
          appView.sortBy = Sort.UID;
          appView.sortData();
          IptablesLog.settings.setSortBy(appView.sortBy);
          break;
        case R.id.sort_by_name:
          appView.sortBy = Sort.NAME;
          appView.sortData();
          IptablesLog.settings.setSortBy(appView.sortBy);
          break;
        case R.id.sort_by_packets:
          appView.sortBy = Sort.PACKETS;
          appView.sortData();
          IptablesLog.settings.setSortBy(appView.sortBy);
          break;
        case R.id.sort_by_bytes:
          appView.sortBy = Sort.BYTES;
          appView.sortData();
          IptablesLog.settings.setSortBy(appView.sortBy);
          break;
        case R.id.sort_by_timestamp:
          appView.sortBy = Sort.TIMESTAMP;
          appView.sortData();
          IptablesLog.settings.setSortBy(appView.sortBy);
          break;
        default:
          return super.onOptionsItemSelected(item);
      }

      return true;
    }

  @Override
    public void onBackPressed() {
      confirmExit(this);
    }

  public void showFilterDialog() {
    Context context = getLocalActivityManager().getCurrentActivity();
    new FilterDialog(context);
  }

  public void confirmExit(Context context) {
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle("Confirm exit")
      .setMessage("Are you sure you want to exit?")
      .setCancelable(true)
      .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          IptablesLog.this.finish();
        }
      })
    .setNegativeButton("No", new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        dialog.cancel();
      }
    });
    AlertDialog alert = builder.create();
    alert.show();
  }

  public void confirmReset(Context context) {
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle("Confirm data reset")
      .setMessage("Are you sure you want to reset data?")
      .setCancelable(true)
      .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          appView.resetData();
          logView.resetData();
        }
      })
    .setNegativeButton("No", new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        dialog.cancel();
      }
    });
    AlertDialog alert = builder.create();
    alert.show();
  }
}