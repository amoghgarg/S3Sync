Specification for an Android app to backup camera photos and videos to AWS S3 bucket. 

## 1. High-Level Architecture Decision Matrix
* **Sync Engine:** Build a custom native implementation using Android's `WorkManager` and the official AWS Mobile SDK. Do NOT use `rclone`, `rsync`, or command-line wrappers.
* **Storage Target:** Force all object uploads directly into the Amazon S3 Glacier storage class (`GLACIER` or `GLACIER_IR`) by appending the `x-amz-storage-class` header to the payload requests.
* **Data Preservation Philosophy:** Prioritize total file preservation over storage costs. Upload ALL physical files discovered, including all standalone images within burst sequences/stacked photos. Stream media through `ContentResolver.openInputStream(uri)` to ensure non-destructive user edits are fully baked into the uploaded asset.

---

## 2. Functional Requirements

### 2.1 Media Discovery & Synchronization
* **Source:** Scan the system camera roll using Android's `MediaStore` API via granular permissions (`READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` on Android 13+).
* **S3 Directory Mapping:** Group objects chronologically into S3 prefixes based on the media's "date taken" metadata string, formatted exactly as: `s3://[bucket-name]/YYYY-MM/[filename]`.
* **Execution Schedules:** * **Periodic:** A configurable background timer managed via `WorkManager` (e.g., hourly, daily, weekly).
    * **Manual Override:** Users must be able to trigger an instantaneous, high-priority background sync for a single, specific `YYYY-MM` month directly from the dashboard.

### 2.2 UI Status Dashboard
* Display a reverse-chronological historical feed grouped by month (e.g., June 2026, May 2026).
* For each month, render:
    1. Total number of photos/videos found locally.
    2. Total successfully backed up to S3 Glacier.
    3. Visual Sync Status Tag: `SYNCED` (local == remote), `PENDING` (local > remote), or `ERROR` (failed uploads present).
    4. A clickable "Sync Now" button to execute an immediate manual override sync for that specific month.

---

## 3. Local Storage Schema (SQLite via Room)

Use a local Room database as the system's "source of truth" ledger to handle state persistence, prevent duplicate uploads, and ensure rapid dashboard rendering without blocking the UI main thread.

```sql
CREATE TABLE media_assets (
    id TEXT PRIMARY KEY NOT NULL,
    local_uri TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_type TEXT NOT NULL,               -- 'PHOTO' or 'VIDEO'
    file_size_bytes INTEGER NOT NULL,
    captured_month TEXT NOT NULL,          -- Format: 'YYYY-MM'
    sync_status TEXT NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'UPLOADING', 'SUCCESS', 'FAILED'
    s3_key TEXT,
    last_error_message TEXT,
    updated_at INTEGER NOT NULL
);
CREATE INDEX idx_media_captured_month ON media_assets(captured_month);
CREATE INDEX idx_media_sync_status ON media_assets(sync_status);

CREATE TABLE monthly_sync_summaries (
    month_key TEXT PRIMARY KEY NOT NULL,   -- Format: 'YYYY-MM'
    total_local_photos INTEGER DEFAULT 0,
    total_local_videos INTEGER DEFAULT 0,
    total_backed_up INTEGER DEFAULT 0,
    sync_state TEXT NOT NULL DEFAULT 'PENDING', -- 'SYNCED', 'PENDING', 'ERROR'
    last_sync_triggered INTEGER
);
```

## Other requirements:

- Automatic backup should only trigger if connected to Wi-Fi.
- Upload failure should be handled with exponential backoff
- The S3 configs should be editable anytime on the Settings page
