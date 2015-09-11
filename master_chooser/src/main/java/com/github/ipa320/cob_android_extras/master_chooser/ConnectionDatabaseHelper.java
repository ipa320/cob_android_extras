package com.github.ipa320.cob_android_extras.master_chooser;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ConnectionDatabaseHelper extends SQLiteOpenHelper {
  private static final String DATABASE_NAME="masterconnections";
  static final String KEY_ID="_id";
  static final String TITLE="title";
  static final String URL="value";
  
  public ConnectionDatabaseHelper(Context context) {
    super(context, DATABASE_NAME, null, 1);
  }
  
  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE connections (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, value TEXT);");
    
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    android.util.Log.w("Connections", "Upgrading database, which will destroy all old data");
    db.execSQL("DROP TABLE IF EXISTS constants");
    onCreate(db);
  }
}