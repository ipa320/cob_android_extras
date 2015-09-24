/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.github.ipa320.cob_android_extras.master_chooser;

import com.google.common.base.Preconditions;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.github.ipa320.cob_android_extras.master_chooser.R;
import org.ros.exception.RosRuntimeException;
import org.ros.internal.node.client.MasterClient;
import org.ros.internal.node.xmlrpc.XmlRpcTimeoutException;
import org.ros.namespace.GraphName;
import org.ros.node.NodeConfiguration;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

/**
 * Allows the user to configue a master {@link URI} then it returns that
 * {@link URI} to the calling {@link Activity}.
 * <p/>
 * When this {@link Activity} is started, the last used (or the default)
 * {@link URI} is displayed to the user.
 *
 * @author ethan.rublee@gmail.com (Ethan Rublee)
 * @author damonkohler@google.com (Damon Kohler)
 * @author munjaldesai@google.com (Munjal Desai)
 * @author benjamin.maidel@ipa.fraunhofer.de (Benjamin Maidel)
 */
public class MasterChooser extends ListActivity implements AdapterView.OnItemSelectedListener {

  /**
   * The key with which the last used {@link URI} will be stored as a
   * preference.
   */
  private static final String PREFS_KEY_NAME = "URI_KEY";

  /**
   * Package name of the QR code reader used to scan QR codes.
   */
  private static final String BAR_CODE_SCANNER_PACKAGE_NAME =
      "com.google.zxing.client.android.SCAN";

  private String selectedInterface;
  private String masterUri = "";
  private String masterTitle = "";

  private static final int ADD_ID = Menu.FIRST + 1;
  private static final int SCAN_ID = Menu.FIRST + 2;
  private static final int EDIT_ID = Menu.FIRST + 3;
  private static final int DELETE_ID = Menu.FIRST + 4;
  private ConnectionDatabaseHelper db = null;
  private Cursor connectionsCursor = null;
  private ListAdapter adapter;

  private class StableArrayAdapter extends ArrayAdapter<String> {

    HashMap<String, Integer> idMap = new HashMap<String, Integer>();

    public StableArrayAdapter(Context context, int textViewResourceId, List<String> objects) {
      super(context, textViewResourceId, objects);
      for (int i = 0; i < objects.size(); ++i) {
        idMap.put(objects.get(i), i);
      }
    }

    @Override
    public long getItemId(int position) {
      String item = getItem(position);
      return idMap.get(item);
    }

    @Override
    public boolean hasStableIds() {
      return true;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.master_chooser);
    ActionBar ab = getActionBar();

    if (ab != null)
      ab.setDisplayShowTitleEnabled(false);
    else {
      findViewById(R.id.linearLayoutButtons).setVisibility(View.VISIBLE);
      Button button_ok = (Button) findViewById(R.id.buttonAdd);
      Button button_qr = (Button) findViewById(R.id.buttonQR);

      button_ok.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          add();
        }
      });
      button_qr.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          IntentIntegrator integrator = new IntentIntegrator(MasterChooser.this);
          integrator.initiateScan(IntentIntegrator.QR_CODE_TYPES);
        }
      });
    }

    this.getListView().setOnItemSelectedListener(this);

    db = new ConnectionDatabaseHelper(this);
    connectionsCursor = db
        .getReadableDatabase()
        .rawQuery("SELECT _ID, title, value " +
                "FROM connections ORDER BY title",
            null);

    adapter = new SimpleCursorAdapter(this,
        R.layout.connection_list_item, connectionsCursor,
        new String[]{ConnectionDatabaseHelper.TITLE,
            ConnectionDatabaseHelper.URL},
        new int[]{R.id.conn_list_item_title, R.id.conn_list_item_url});
    this.setListAdapter(adapter);
    this.registerForContextMenu(getListView());

    ListView interfacesList = (ListView) findViewById(R.id.networkInterfaces);
    final List<String> list = new ArrayList<String>();

    try {
      for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (networkInterface.isUp() && !networkInterface.isLoopback()) {
          list.add(networkInterface.getName());
        }
      }
    } catch (SocketException e) {
      throw new RosRuntimeException(e);
    }

    // Fallback to previous behaviour when no interface is selected.
    selectedInterface = "";

    final StableArrayAdapter adapter = new StableArrayAdapter(this, android.R.layout.simple_list_item_1, list);
    interfacesList.setAdapter(adapter);

    interfacesList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        selectedInterface = parent.getItemAtPosition(position).toString();
      }
    });
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuItem itemAdd = menu.add(Menu.NONE, ADD_ID, Menu.NONE, "Add")
        .setIcon(R.drawable.ic_action_new)
        .setAlphabeticShortcut('a');
    MenuItem itemScan = menu.add(Menu.NONE, SCAN_ID, Menu.NONE, "Scan")
        .setIcon(R.drawable.ic_action_new_picture);

    itemAdd.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    itemScan.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

    return (super.onCreateOptionsMenu(menu));
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case ADD_ID:
        add();
        return (true);
      case SCAN_ID:

        IntentIntegrator integrator = new IntentIntegrator(MasterChooser.this);
        integrator.initiateScan(IntentIntegrator.QR_CODE_TYPES);
        return (true);
    }

    return (super.onOptionsItemSelected(item));
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v,
                                  ContextMenu.ContextMenuInfo menuInfo) {
    menu.add(Menu.NONE, EDIT_ID, Menu.NONE, "Edit")
        .setAlphabeticShortcut('e');
    menu.add(Menu.NONE, DELETE_ID, Menu.NONE, "Delete")
        .setAlphabeticShortcut('d');
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    AdapterView.AdapterContextMenuInfo info;
    switch (item.getItemId()) {
      case EDIT_ID:
        info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        edit(info.id);
        return (true);

      case DELETE_ID:
        info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        delete(info.id);
        return (true);
    }
    return (super.onOptionsItemSelected(item));
  }

  private void add() {
    LayoutInflater inflater = LayoutInflater.from(this);
    View addView = inflater.inflate(R.layout.add_edit_connection, null);
    final DialogWrapper wrapper = new DialogWrapper(addView);

    wrapper.setUrl("http://");

    new AlertDialog.Builder(this)
        .setTitle(R.string.add_edit_title)
        .setView(addView)
        .setPositiveButton(R.string.ok,
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog,
                                  int whichButton) {
                processAdd(wrapper);
              }
            })
        .setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog,
                                      int whichButton) {
                    // ignore, just dismiss
                  }
                })
        .show();
  }

  private void edit(final long rowId) {
    LayoutInflater inflater = LayoutInflater.from(this);
    View addView = inflater.inflate(R.layout.add_edit_connection, null);
    final DialogWrapper wrapper = new DialogWrapper(addView);
    String[] columns = new String[]{
        ConnectionDatabaseHelper.KEY_ID,
        ConnectionDatabaseHelper.TITLE,
        ConnectionDatabaseHelper.URL};
    Cursor cursor = db.getReadableDatabase().query("connections", columns,
        ConnectionDatabaseHelper.KEY_ID + "=?", new String[]{String.valueOf(rowId)},
        null, null, null, null);
    if (cursor != null)
      cursor.moveToFirst();

    wrapper.setTitle(cursor.getString(1));
    wrapper.setUrl(cursor.getString(2));

    new AlertDialog.Builder(this)
        .setTitle(R.string.add_edit_title)
        .setView(addView)
        .setPositiveButton(R.string.ok,
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog,
                                  int whichButton) {
                processEdit(wrapper, rowId);
              }
            })
        .setNegativeButton(R.string.cancel,
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog,
                                  int whichButton) {
                // ignore, just dismiss
              }
            })
        .show();
  }

  private void delete(final long rowId) {
    if (rowId > 0) {
      new AlertDialog.Builder(this)
          .setTitle(R.string.delete_title)
          .setPositiveButton(R.string.ok,
              new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog,
                                    int whichButton) {
                  processDelete(rowId);
                }
              })
          .setNegativeButton(R.string.cancel,
              new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog,
                                    int whichButton) {
                  // ignore, just dismiss
                }
              })
          .show();
    }
  }

  private void processAdd(DialogWrapper wrapper) {
    ContentValues values = new ContentValues(2);

    values.put(ConnectionDatabaseHelper.TITLE, wrapper.getTitle());
    values.put(ConnectionDatabaseHelper.URL, wrapper.getUrl());

    db.getWritableDatabase().insert("connections", ConnectionDatabaseHelper.TITLE, values);
    //refreshListView();
    connectionsCursor.requery();
  }

  private void processEdit(DialogWrapper wrapper, final long rowId) {
    ContentValues values = new ContentValues(2);

    values.put(ConnectionDatabaseHelper.TITLE, wrapper.getTitle());
    values.put(ConnectionDatabaseHelper.URL, wrapper.getUrl());

    db.getWritableDatabase().update("connections", values, ConnectionDatabaseHelper.KEY_ID + " = ?",
        new String[]{String.valueOf(rowId)});
    //refreshListView();
    connectionsCursor.requery();
  }

  private void processDelete(long rowId) {
    String[] args = {String.valueOf(rowId)};

    db.getWritableDatabase().delete("connections", "_ID=?", args);
    //refreshListView();
    connectionsCursor.requery();
  }

  class DialogWrapper {
    EditText titleField = null;
    EditText urlField = null;
    View base = null;

    DialogWrapper(View base) {
      this.base = base;
      urlField = (EditText) base.findViewById(R.id.add_edit_url);
      titleField = (EditText) base.findViewById(R.id.add_edit_title);
    }

    void setTitle(String value) {
      getTitleField().setText(value);
    }

    void setUrl(String value) {
      getUrlField().setText(value);
    }

    String getTitle() {
      return (getTitleField().getText().toString());
    }

    String getUrl() {
      return (getUrlField().getText().toString());
    }

    private EditText getTitleField() {
      if (titleField == null) {
        titleField = (EditText) base.findViewById(R.id.add_edit_title);
      }

      return (titleField);
    }

    private EditText getUrlField() {
      if (urlField == null) {
        urlField = (EditText) base.findViewById(R.id.add_edit_url);
      }

      return (urlField);
    }
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
    if (result != null) {
      String contents = result.getContents();
      if (contents != null) {
        String lines[] = contents.split("[\r\n]+");
        if (lines.length == 2) {
          ContentValues values = new ContentValues(2);
          values.put(ConnectionDatabaseHelper.TITLE, lines[0]);
          values.put(ConnectionDatabaseHelper.URL, lines[1]);

          db.getWritableDatabase().insert("connections", ConnectionDatabaseHelper.TITLE, values);
          connectionsCursor.requery();
        }
      }
    }
  }

  @Override
  protected void onListItemClick(ListView l, View v, int position, long id) {
    if (position >= 0) {
      //Get current cursor
      // Get the current text entered for URI.
      TextView tvUri = (TextView) v.findViewById(R.id.conn_list_item_url);
      masterUri = tvUri.getText().toString();
      TextView tvTitle = (TextView) v.findViewById(R.id.conn_list_item_title);
      masterTitle = tvTitle.getText().toString();

      // Make sure the URI can be parsed correctly and that the master is
      // reachable.
      new AsyncTask<Void, Void, Boolean>() {
        @Override
        protected Boolean doInBackground(Void... params) {
          try {
            toast("Trying to reach master at " + masterUri, Toast.LENGTH_LONG);
            String uri = masterUri.replace("http://","");
            uri = uri.replace(":11311","");
            Log.i("MasterChooser", "Trying to reach master at " + uri);
            InetAddress[] inetAddress = InetAddress.getAllByName(uri);
            try {
              Thread.sleep(2000,0);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          } catch(UnknownHostException e){
            toast("Unknown host: "+e.toString(), Toast.LENGTH_LONG);
            Log.e("MasterChooser", "Unknown Host: "+e.toString());
            return false;
          }
          try
          {
            MasterClient masterClient = new MasterClient(new URI(masterUri));
            masterClient.getUri(GraphName.of("android/master_chooser_activity"));
            toast("Connected!", Toast.LENGTH_LONG);
            return true;
          } catch (URISyntaxException e) {
            toast("Invalid URI.", Toast.LENGTH_LONG);
            return false;
          } catch (XmlRpcTimeoutException e) {
            toast("Master unreachable!", Toast.LENGTH_LONG);
            return false;
          }
        }

        @Override
        protected void onPostExecute(Boolean result) {
          if (result) {
            // Package the intent to be consumed by the calling activity.
            Intent intent = createNewMasterIntent(false, true);
            setResult(RESULT_OK, intent);
            finish();
          } else {
          }
        }
      }.execute();
    }
  }

  @Override
  public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
    if (position >= 0) {
      //Get current cursor
      // Get the current text entered for URI.
      TextView tvUri = (TextView) v.findViewById(R.id.conn_list_item_url);
      masterUri = tvUri.getText().toString();
      TextView tvTitle = (TextView) v.findViewById(R.id.conn_list_item_title);
      masterTitle = tvTitle.getText().toString();

      // Make sure the URI can be parsed correctly and that the master is
      // reachable.
      new AsyncTask<Void, Void, Boolean>() {
        @Override
        protected Boolean doInBackground(Void... params) {
          try {
            toast("Trying to reach master at " + masterUri, Toast.LENGTH_LONG);
            String uri = masterUri.replace("http://","");
            uri = uri.replace(":11311","");
            Log.i("MasterChooser", "Trying to reach master at " + uri);
            InetAddress[] inetAddress = InetAddress.getAllByName(uri);
            try {
              Thread.sleep(2000,0);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          } catch(UnknownHostException e){
            toast("Unknown host: "+e.toString(), Toast.LENGTH_LONG);
            Log.e("MasterChooser", "Unknown Host: "+e.toString());
            return false;
          }
          try
          {
            MasterClient masterClient = new MasterClient(new URI(masterUri));
            masterClient.getUri(GraphName.of("android/master_chooser_activity"));
            toast("Connected!", Toast.LENGTH_LONG);
            return true;
          } catch (URISyntaxException e) {
            toast("Invalid URI.", Toast.LENGTH_LONG);
            return false;
          } catch (XmlRpcTimeoutException e) {
            toast("Master unreachable!", Toast.LENGTH_LONG);
            return false;
          }
        }

        @Override
        protected void onPostExecute(Boolean result) {
          if (result) {
            // Package the intent to be consumed by the calling activity.
            Intent intent = createNewMasterIntent(false, true);
            setResult(RESULT_OK, intent);
            finish();
          } else {
          }
        }
      }.execute();
    }
  }

  @Override
  public void onNothingSelected(AdapterView<?> adapterView) {

  }

  private void toast(final String text, final int length) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(MasterChooser.this, text, length).show();
      }
    });
  }

  public void qrCodeButtonClicked(View unused) {
    Intent intent = new Intent(BAR_CODE_SCANNER_PACKAGE_NAME);
    intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
    // Check if the Barcode Scanner is installed.
    if (!isQRCodeReaderInstalled(intent)) {
      // Open the Market and take them to the page from which they can download the Barcode Scanner
      // app.
      startActivity(new Intent(Intent.ACTION_VIEW,
          Uri.parse("market://details?id=com.google.zxing.client.android")));
    } else {
      // Call the Barcode Scanner to let the user scan a QR code.
      startActivityForResult(intent, 0);
    }
  }

  public void advancedCheckboxClicked(View view) {
    boolean checked = ((CheckBox) view).isChecked();
    LinearLayout advancedOptions = (LinearLayout) findViewById(R.id.advancedOptions);
    if (checked) {
      advancedOptions.setVisibility(View.VISIBLE);
    } else {
      advancedOptions.setVisibility(View.GONE);
    }
  }

  public Intent createNewMasterIntent(boolean newMaster, boolean isPrivate) {
    Intent intent = new Intent();

    intent.putExtra("ROS_MASTER_CREATE_NEW", newMaster);
    intent.putExtra("ROS_MASTER_PRIVATE", isPrivate);
    intent.putExtra("ROS_MASTER_URI", masterUri);
    intent.putExtra("ROS_MASTER_TITLE", masterTitle);
    intent.putExtra("ROS_MASTER_NETWORK_INTERFACE", selectedInterface);
    return intent;
  }

  public void newMasterButtonClicked(View unused) {
    setResult(RESULT_OK, createNewMasterIntent(true, false));
    finish();
  }

  public void newPrivateMasterButtonClicked(View unused) {
    setResult(RESULT_OK, createNewMasterIntent(true, true));
    finish();
  }

  public void cancelButtonClicked(View unused) {
    setResult(RESULT_CANCELED);
    finish();
  }

  /**
   * Check if the specified app is installed.
   *
   * @param intent The activity that you wish to look for.
   * @return true if the desired activity is install on the device, false
   * otherwise.
   */
  private boolean isQRCodeReaderInstalled(Intent intent) {
    List<ResolveInfo> list =
        getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
    return (list.size() > 0);
  }
}
