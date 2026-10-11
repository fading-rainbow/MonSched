# Room resolves its generated implementation by class name.
-keep class cn.qingke.schedule.data.AppDatabase_Impl { *; }
# WorkManager persists this class name between signed upgrades.
-keep class cn.qingke.schedule.sync.OfficialSyncWorker { public <init>(...); }
