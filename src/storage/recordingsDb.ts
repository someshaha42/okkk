import type { RecordedVideoItem } from '../types/recorder';

const DB_NAME = 'framecut_recordings_db';
const DB_VERSION = 1;
const STORE_NAME = 'recordings';

interface StoredRecordingRecord extends RecordedVideoItem {
  blob: Blob;
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' });
        store.createIndex('dateAddedSec', 'dateAddedSec', { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('Failed to open recordings database'));
  });
}

export async function loadRecordings(): Promise<RecordedVideoItem[]> {
  try {
    const db = await openDatabase();
    return await new Promise<RecordedVideoItem[]>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.getAll();
      req.onsuccess = () => {
        const records = (req.result as StoredRecordingRecord[]) ?? [];
        const items: RecordedVideoItem[] = records
          .map(({ blob: _blob, ...meta }) => meta)
          .sort((a, b) => b.dateAddedSec - a.dateAddedSec);
        resolve(items);
      };
      req.onerror = () => reject(req.error ?? new Error('Failed to read recordings'));
      tx.oncomplete = () => db.close();
    });
  } catch {
    return [];
  }
}

export async function saveRecording(
  item: RecordedVideoItem,
  blob: Blob,
): Promise<void> {
  const db = await openDatabase();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    const record: StoredRecordingRecord = { ...item, blob };
    const req = store.put(record);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error ?? new Error('Failed to save recording'));
    tx.oncomplete = () => db.close();
  });
}

export async function getRecordingBlob(id: string): Promise<Blob | null> {
  try {
    const db = await openDatabase();
    return await new Promise<Blob | null>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.get(id);
      req.onsuccess = () => {
        const rec = req.result as StoredRecordingRecord | undefined;
        resolve(rec?.blob ?? null);
      };
      req.onerror = () => reject(req.error ?? new Error('Failed to fetch recording blob'));
      tx.oncomplete = () => db.close();
    });
  } catch {
    return null;
  }
}

export async function deleteRecording(id: string): Promise<boolean> {
  try {
    const db = await openDatabase();
    return await new Promise<boolean>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const req = store.delete(id);
      req.onsuccess = () => resolve(true);
      req.onerror = () => reject(req.error ?? new Error('Failed to delete recording'));
      tx.oncomplete = () => db.close();
    });
  } catch {
    return false;
  }
}
