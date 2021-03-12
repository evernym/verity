package com.evernym.verity.libindy.wallet;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

public abstract class MySqlStorageLib {

    //private String type = "mysql";
    public static API api = null;
    private static String LIBRARY_NAME = "mysqlstorage";


    /**
     * JNA method signatures for calling SDK function.
     */
    public interface API extends Library {
        int mysql_storage_init ();
    }

    public static void init(String searchPath) {
        NativeLibrary.addSearchPath(LIBRARY_NAME, searchPath);
        api = Native.load(LIBRARY_NAME, API.class);
        api.mysql_storage_init();
    }

    /**
     * Indicates whether or not the API has been initialized.
     *
     * @return true if the API is initialize, otherwise false.
     */
    public static boolean isInitialized() {
        return api != null;
    }
}
