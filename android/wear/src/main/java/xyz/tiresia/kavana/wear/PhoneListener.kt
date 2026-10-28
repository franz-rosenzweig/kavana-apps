package xyz.tiresia.kavana.wear

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** The phone's snapshot, as it changes: kept, and the tile and the complications drawn again. */
class PhoneListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != "/chataltul/snap") continue
            val map = DataMapItem.fromDataItem(e.dataItem).dataMap
            val snap = map.getString("json") ?: continue
            val png = map.getAsset("window")?.let { a ->
                runCatching { Tasks.await(Wearable.getDataClient(this).getFdForAsset(a)).inputStream.use { it.readBytes() } }.getOrNull()
            }
            WatchStore.put(this, snap, png)
        }
    }
}
