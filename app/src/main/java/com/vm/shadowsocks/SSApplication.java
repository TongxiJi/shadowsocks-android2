package com.vm.shadowsocks;

import android.app.Application;
import android.content.Context;

/**
 * Author:tonnyji
 * E-MAIL:694270875@qq.com
 * Function:
 * Create Date:八月15,2018
 */
public class SSApplication extends Application {
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        SSApplication.context = getApplicationContext();
    }

    public static Context getAppContext() {
        return SSApplication.context;
    }
}
