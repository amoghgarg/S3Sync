# S3 Sync

S3 Sync is an Android application designed to automatically back up camera photos and videos directly to an Amazon S3 bucket using the Glacier storage class for cost-effective long-term preservation.

This is a AI generated project with no human review of the generated code. 

## Features

- **Manual Override:** Trigger immediate synchronization for specific months directly from the dashboard.
- **Glacier Storage:** Automatically uploads files with the `GLACIER` storage class to minimize costs.
- **Chronological Organization:** Files are organized in S3 by month (e.g., `s3://bucket/YYYY-MM/filename`).
- **Status Dashboard:** Real-time visibility into local file counts, upload status, and sync history for the last 6 months.
- **Smart Syncing:** Only triggers automatic backups when connected to Wi-Fi.
- **Resilient Uploads:** Implements exponential backoff for handling network failures.

## Tech Stack

- **UI:** Jetpack Compose with Material 3.
- **Architecture:** MVVM with Clean Architecture principles.
- **Database:** Room (SQLite) for tracking file status and sync state.
- **Sync Engine:** Android WorkManager.
- **AWS Integration:** Official AWS SDK for Kotlin.
- **Media Discovery:** Android MediaStore API.

## Getting Started

1.  **Configure S3:** Go to the Settings screen in the app and enter your AWS credentials (Access Key, Secret Key, Region) and target Bucket Name.
2.  **Permissions:** Grant the necessary media permissions for the app to discover your photos and videos.
3.  **Sync:** The app will automatically start syncing on Wi-Fi based on the configured schedule, or you can manually sync any month from the Dashboard.
