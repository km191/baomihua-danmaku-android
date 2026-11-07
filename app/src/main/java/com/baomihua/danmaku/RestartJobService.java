package com.baomihua.danmaku;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/**
 * 后台看护：ColorOS 的「应用速冻」和内存回收可能在没有任何回调的情况下清掉服务。
 *
 * <p>这里挂一个 15 分钟的持久化 Job（系统重启后仍然存在）。每次触发只做一件事：
 * 如果用户还开着悬浮窗、权限也还在，但服务没了，就把它拉回来。不做常驻循环，也不耗电。
 */
public final class RestartJobService extends JobService {

    private static final int JOB_ID = 0x1D10;
    /** JobScheduler 允许的最小周期。 */
    private static final long PERIOD_MS = 15L * 60L * 1000L;

    public static void ensureScheduled(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler)
                    context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && scheduler.getPendingJob(JOB_ID) != null) {
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, RestartJobService.class))
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .build();
            int result = scheduler.schedule(job);
            Diag.add("后台看护任务已登记 result=" + result);
        } catch (Throwable e) {
            Diag.error("登记后台看护任务失败", e);
        }
    }

    public static void cancel(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler)
                    context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) {
                scheduler.cancel(JOB_ID);
                Diag.add("后台看护任务已取消");
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Prefs prefs = App.prefs(this);
        if (!prefs.isServiceWanted()) {
            return false;
        }
        if (OverlayService.isRunning()) {
            return false;
        }
        if (!App.canDrawOverlays(this)) {
            Diag.add("看护任务：悬浮窗权限没了，不拉起");
            return false;
        }
        try {
            OverlayService.start(this);
            Diag.add("看护任务把悬浮窗拉回来了");
        } catch (Throwable e) {
            Diag.error("看护任务启动失败", e);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
