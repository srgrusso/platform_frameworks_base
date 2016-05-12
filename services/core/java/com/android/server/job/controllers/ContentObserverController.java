/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.job.controllers;

import android.app.job.JobInfo;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.util.TimeUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Controller for monitoring changes to content URIs through a ContentObserver.
 */
public class ContentObserverController extends StateController {
    private static final String TAG = "JobScheduler.Content";

    /**
     * Maximum number of changing URIs we will batch together to report.
     * XXX Should be smarter about this, restricting it by the maximum number
     * of characters we will retain.
     */
    private static final int MAX_URIS_REPORTED = 50;

    /**
     * At this point we consider it urgent to schedule the job ASAP.
     */
    private static final int URIS_URGENT_THRESHOLD = 40;

    private static final Object sCreationLock = new Object();
    private static volatile ContentObserverController sController;

    final private List<JobStatus> mTrackedTasks = new ArrayList<JobStatus>();
    ArrayMap<Uri, ObserverInstance> mObservers = new ArrayMap<>();
    final Handler mHandler;

    public static ContentObserverController get(JobSchedulerService taskManagerService) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new ContentObserverController(taskManagerService,
                        taskManagerService.getContext(), taskManagerService.getLock());
            }
        }
        return sController;
    }

    @VisibleForTesting
    public static ContentObserverController getForTesting(StateChangedListener stateChangedListener,
                                           Context context) {
        return new ContentObserverController(stateChangedListener, context, new Object());
    }

    private ContentObserverController(StateChangedListener stateChangedListener, Context context,
                Object lock) {
        super(stateChangedListener, context, lock);
        mHandler = new Handler(context.getMainLooper());
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasContentTriggerConstraint()) {
            if (taskStatus.contentObserverJobInstance == null) {
                taskStatus.contentObserverJobInstance = new JobInstance(taskStatus);
            }
            mTrackedTasks.add(taskStatus);
            boolean havePendingUris = false;
            // If there is a previous job associated with the new job, propagate over
            // any pending content URI trigger reports.
            if (taskStatus.contentObserverJobInstance.mChangedAuthorities != null) {
                havePendingUris = true;
            }
            // If we have previously reported changed authorities/uris, then we failed
            // to complete the job with them so will re-record them to report again.
            if (taskStatus.changedAuthorities != null) {
                havePendingUris = true;
                if (taskStatus.contentObserverJobInstance.mChangedAuthorities == null) {
                    taskStatus.contentObserverJobInstance.mChangedAuthorities
                            = new ArraySet<>();
                }
                for (String auth : taskStatus.changedAuthorities) {
                    taskStatus.contentObserverJobInstance.mChangedAuthorities.add(auth);
                }
                if (taskStatus.changedUris != null) {
                    if (taskStatus.contentObserverJobInstance.mChangedUris == null) {
                        taskStatus.contentObserverJobInstance.mChangedUris = new ArraySet<>();
                    }
                    for (Uri uri : taskStatus.changedUris) {
                        taskStatus.contentObserverJobInstance.mChangedUris.add(uri);
                    }
                }
                taskStatus.changedAuthorities = null;
                taskStatus.changedUris = null;
            }
            taskStatus.changedAuthorities = null;
            taskStatus.changedUris = null;
            taskStatus.setContentTriggerConstraintSatisfied(havePendingUris);
        }
        if (lastJob != null && lastJob.contentObserverJobInstance != null) {
            // And now we can detach the instance state from the last job.
            lastJob.contentObserverJobInstance.detachLocked();
            lastJob.contentObserverJobInstance = null;
        }
    }

    @Override
    public void prepareForExecutionLocked(JobStatus taskStatus) {
        if (taskStatus.hasContentTriggerConstraint()) {
            if (taskStatus.contentObserverJobInstance != null) {
                taskStatus.changedUris = taskStatus.contentObserverJobInstance.mChangedUris;
                taskStatus.changedAuthorities
                        = taskStatus.contentObserverJobInstance.mChangedAuthorities;
                taskStatus.contentObserverJobInstance.mChangedUris = null;
                taskStatus.contentObserverJobInstance.mChangedAuthorities = null;
            }
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (taskStatus.hasContentTriggerConstraint()) {
            if (taskStatus.contentObserverJobInstance != null) {
                taskStatus.contentObserverJobInstance.unscheduleLocked();
                if (incomingJob != null) {
                    if (taskStatus.contentObserverJobInstance != null
                            && taskStatus.contentObserverJobInstance.mChangedAuthorities != null) {
                        // We are stopping this job, but it is going to be replaced by this given
                        // incoming job.  We want to propagate our state over to it, so we don't
                        // lose any content changes that had happend since the last one started.
                        // If there is a previous job associated with the new job, propagate over
                        // any pending content URI trigger reports.
                        if (incomingJob.contentObserverJobInstance == null) {
                            incomingJob.contentObserverJobInstance = new JobInstance(incomingJob);
                        }
                        incomingJob.contentObserverJobInstance.mChangedAuthorities
                                = taskStatus.contentObserverJobInstance.mChangedAuthorities;
                        incomingJob.contentObserverJobInstance.mChangedUris
                                = taskStatus.contentObserverJobInstance.mChangedUris;
                        taskStatus.contentObserverJobInstance.mChangedAuthorities = null;
                        taskStatus.contentObserverJobInstance.mChangedUris = null;
                    }
                    // We won't detach the content observers here, because we want to
                    // allow them to continue monitoring so we don't miss anything...  and
                    // since we are giving an incomingJob here, we know this will be
                    // immediately followed by a start tracking of that job.
                } else {
                    // But here there is no incomingJob, so nothing coming up, so time to detach.
                    taskStatus.contentObserverJobInstance.detachLocked();
                    taskStatus.contentObserverJobInstance = null;
                }
            }
            mTrackedTasks.remove(taskStatus);
        }
    }

    @Override
    public void rescheduleForFailure(JobStatus newJob, JobStatus failureToReschedule) {
        if (failureToReschedule.hasContentTriggerConstraint()
                && newJob.hasContentTriggerConstraint()) {
            synchronized (mLock) {
                // Our job has failed, and we are scheduling a new job for it.
                // Copy the last reported content changes in to the new job, so when
                // we schedule the new one we will pick them up and report them again.
                newJob.changedAuthorities = failureToReschedule.changedAuthorities;
                newJob.changedUris = failureToReschedule.changedUris;
            }
        }
    }

    final class ObserverInstance extends ContentObserver {
        final Uri mUri;
        final ArraySet<JobInstance> mJobs = new ArraySet<>();

        public ObserverInstance(Handler handler, Uri uri) {
            super(handler);
            mUri = uri;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                final int N = mJobs.size();
                for (int i=0; i<N; i++) {
                    JobInstance inst = mJobs.valueAt(i);
                    if (inst.mChangedUris == null) {
                        inst.mChangedUris = new ArraySet<>();
                    }
                    if (inst.mChangedUris.size() < MAX_URIS_REPORTED) {
                        inst.mChangedUris.add(uri);
                    }
                    if (inst.mChangedAuthorities == null) {
                        inst.mChangedAuthorities = new ArraySet<>();
                    }
                    inst.mChangedAuthorities.add(uri.getAuthority());
                    inst.scheduleLocked();
                }
            }
        }
    }

    static final class TriggerRunnable implements Runnable {
        final JobInstance mInstance;

        TriggerRunnable(JobInstance instance) {
            mInstance = instance;
        }

        @Override public void run() {
            mInstance.trigger();
        }
    }

    final class JobInstance {
        final ArrayList<ObserverInstance> mMyObservers = new ArrayList<>();
        final JobStatus mJobStatus;
        final Runnable mExecuteRunner;
        final Runnable mTimeoutRunner;
        ArraySet<Uri> mChangedUris;
        ArraySet<String> mChangedAuthorities;

        boolean mTriggerPending;

        JobInstance(JobStatus jobStatus) {
            mJobStatus = jobStatus;
            mExecuteRunner = new TriggerRunnable(this);
            mTimeoutRunner = new TriggerRunnable(this);
            final JobInfo.TriggerContentUri[] uris = jobStatus.getJob().getTriggerContentUris();
            if (uris != null) {
                for (JobInfo.TriggerContentUri uri : uris) {
                    ObserverInstance obs = mObservers.get(uri.getUri());
                    if (obs == null) {
                        obs = new ObserverInstance(mHandler, uri.getUri());
                        mObservers.put(uri.getUri(), obs);
                        mContext.getContentResolver().registerContentObserver(
                                uri.getUri(),
                                (uri.getFlags() &
                                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)
                                    != 0,
                                obs);
                    }
                    obs.mJobs.add(this);
                    mMyObservers.add(obs);
                }
            }
        }

        void trigger() {
            boolean reportChange = false;
            synchronized (mLock) {
                if (mTriggerPending) {
                    if (mJobStatus.setContentTriggerConstraintSatisfied(true)) {
                        reportChange = true;
                    }
                    unscheduleLocked();
                }
            }
            // Let the scheduler know that state has changed. This may or may not result in an
            // execution.
            if (reportChange) {
                mStateChangedListener.onControllerStateChanged();
            }
        }

        void scheduleLocked() {
            if (!mTriggerPending) {
                mTriggerPending = true;
                mHandler.postDelayed(mTimeoutRunner, mJobStatus.getTriggerContentMaxDelay());
            }
            mHandler.removeCallbacks(mExecuteRunner);
            if (mChangedUris.size() >= URIS_URGENT_THRESHOLD) {
                // If we start getting near the limit, GO NOW!
                mHandler.post(mExecuteRunner);
            } else {
                mHandler.postDelayed(mExecuteRunner, mJobStatus.getTriggerContentUpdateDelay());
            }
        }

        void unscheduleLocked() {
            if (mTriggerPending) {
                mHandler.removeCallbacks(mExecuteRunner);
                mHandler.removeCallbacks(mTimeoutRunner);
                mTriggerPending = false;
            }
        }

        void detachLocked() {
            final int N = mMyObservers.size();
            for (int i=0; i<N; i++) {
                final ObserverInstance obs = mMyObservers.get(i);
                obs.mJobs.remove(this);
                if (obs.mJobs.size() == 0) {
                    mContext.getContentResolver().unregisterContentObserver(obs);
                    mObservers.remove(obs.mUri);
                }
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.println("Content.");
        boolean printed = false;
        Iterator<JobStatus> it = mTrackedTasks.iterator();
        while (it.hasNext()) {
            JobStatus js = it.next();
            if (!js.shouldDump(filterUid)) {
                continue;
            }
            if (!printed) {
                pw.print("  ");
                printed = true;
            } else {
                pw.print(",");
            }
            pw.print(System.identityHashCode(js));
        }
        if (printed) {
            pw.println();
        }
        int N = mObservers.size();
        if (N > 0) {
            pw.println("  Observers:");
            for (int i = 0; i < N; i++) {
                ObserverInstance obs = mObservers.valueAt(i);
                int M = obs.mJobs.size();
                boolean shouldDump = false;
                for (int j=0; j<M; j++) {
                    JobInstance inst = obs.mJobs.valueAt(j);
                    if (inst.mJobStatus.shouldDump(filterUid)) {
                        shouldDump = true;
                        break;
                    }
                }
                if (!shouldDump) {
                    continue;
                }
                pw.print("    ");
                pw.print(mObservers.keyAt(i));
                pw.print(" (");
                pw.print(System.identityHashCode(obs));
                pw.println("):");
                pw.println("      Jobs:");
                for (int j=0; j<M; j++) {
                    JobInstance inst = obs.mJobs.valueAt(j);
                    pw.print("        ");
                    pw.print(System.identityHashCode(inst.mJobStatus));
                    if (inst.mChangedAuthorities != null) {
                        pw.println(":");
                        if (inst.mTriggerPending) {
                            pw.print("          Trigger pending: update=");
                            TimeUtils.formatDuration(
                                    inst.mJobStatus.getTriggerContentUpdateDelay(), pw);
                            pw.print(", max=");
                            TimeUtils.formatDuration(
                                    inst.mJobStatus.getTriggerContentMaxDelay(), pw);
                            pw.println();
                        }
                        pw.println("          Changed Authorities:");
                        for (int k=0; k<inst.mChangedAuthorities.size(); k++) {
                            pw.print("          ");
                            pw.println(inst.mChangedAuthorities.valueAt(k));
                        }
                        if (inst.mChangedUris != null) {
                            pw.println("          Changed URIs:");
                            for (int k = 0; k<inst.mChangedUris.size(); k++) {
                                pw.print("          ");
                                pw.println(inst.mChangedUris.valueAt(k));
                            }
                        }
                    } else {
                        pw.println();
                    }
                }
            }
        }
    }
}
