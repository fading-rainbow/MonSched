package cn.qingke.schedule

import android.app.Application
import cn.qingke.schedule.reminders.Reminders

class QingKeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
    }
}
