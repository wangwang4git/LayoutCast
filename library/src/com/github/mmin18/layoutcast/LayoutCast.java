package com.github.mmin18.layoutcast;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.github.mmin18.layoutcast.context.OverrideContext;
import com.github.mmin18.layoutcast.inflater.BootInflater;
import com.github.mmin18.layoutcast.server.LcastServer;
import com.github.mmin18.layoutcast.util.ArtUtils;
import com.github.mmin18.layoutcast.util.ResUtils;

import java.io.File;

public class LayoutCast {

	private static boolean inited;
	private static Context appContext;

	public static void init(Context context) {
		if (inited)
			return;

		Application app = context instanceof Application ? (Application) context
				: (Application) context.getApplicationContext();
		appContext = app;

		// 清空lcast缓存目录
		LcastServer.cleanCache(app);
		File dir = new File(app.getCacheDir(), "lcast");
		File dex = new File(dir, "dex.ped");
		File res = new File(dir, "res.ped");

		if (dex.length() > 0) {
			File f = new File(dir, "dex.apk");
			dex.renameTo(f);
			File opt = new File(dir, "opt");
			opt.mkdirs();
			final String vmVersion = System.getProperty("java.vm.version");
			if (vmVersion != null && vmVersion.startsWith("2")) {
				// dex路径注入，代码变更时，IDE push dex文件到指定路径，重启app应用变更，实现冷部署
				ArtUtils.overrideClassLoader(app.getClassLoader(), f, opt);
			} else {
				Log.e("lcast", "cannot cast dex to daivik, only support ART now.");
			}
		}

		// activity生命周期监听，主要目的：获取activity栈目前有哪一些activity；目前top activity是哪一个
		OverrideContext.initApplication(app);
		// 系统LayoutInflater替换，作用是啥？
		BootInflater.initApplication(app);

		if (res.length() > 0) {
			try {
				File f = new File(dir, "res.apk");
				res.renameTo(f);
				// 注入资源路径，获取新的资源对象
				Resources r = ResUtils.getResources(app, f);
				// 获取所有的activity，如果没有替换context，先来一次自定义context替换，再完成自定义context中资源对象的替换
				// 那么activity中拿到的context都是自定义context，获取资源的都是由自定义context返回，实现资源温部署
				// 顶部的activity来一次重启，用于重新加载资源
				OverrideContext.setGlobalResources(r);
			} catch (Exception e) {
				Log.e("lcast", "fail to cast " + res, e);
			}
		}

		LcastServer.app = app;
		// 启动http服务，接受IDE发送的资源、dex等文件，接受操作命令，完成冷部署、温部署
		LcastServer.start(app);

		inited = true;
	}

	public static boolean restart(boolean confirm) {
		Context top = OverrideContext.getTopActivity();
		if (top instanceof ResetActivity) {
			((ResetActivity) top).reset();
			return true;
		} else {
			Context ctx = appContext;
			try {
				Intent i = new Intent(ctx, ResetActivity.class);
				i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				i.putExtra("reset", confirm);
				ctx.startActivity(i);
				return true;
			} catch (Exception e) {
				final String str = "Fail to cast dex, make sure you have <Activity android:name=\"" + ResetActivity.class.getName() + "\"/> registered in AndroidManifest.xml";
				Log.e("lcast", str);
				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						Toast.makeText(appContext, str, Toast.LENGTH_LONG).show();
					}
				});
				return false;
			}
		}
	}
}
