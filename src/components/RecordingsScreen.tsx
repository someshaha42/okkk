import { useEffect, useState } from 'react';
import {
  ArrowLeft,
  Film,
  Play,
  Share2,
  Trash2,
  X,
} from 'lucide-react';
import type { RecordedVideoItem } from '../types/recorder';
import { getRecordingBlob } from '../storage/recordingsDb';
import { formatDate, formatDuration, formatFileSize } from '../utils/regionMath';

interface RecordingsScreenProps {
  recordings: RecordedVideoItem[];
  isLoading: boolean;
  onBack: () => void;
  onDelete: (item: RecordedVideoItem) => void;
}

export function RecordingsScreen({
  recordings,
  isLoading,
  onBack,
  onDelete,
}: RecordingsScreenProps) {
  const [playingItem, setPlayingItem] = useState<RecordedVideoItem | null>(null);
  const [pendingDelete, setPendingDelete] = useState<RecordedVideoItem | null>(null);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (pendingDelete) {
          setPendingDelete(null);
        } else if (playingItem) {
          setPlayingItem(null);
        } else {
          onBack();
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onBack, pendingDelete, playingItem]);

  const handleShareOrDownload = async (item: RecordedVideoItem) => {
    const blob = await getRecordingBlob(item.id);
    if (!blob) return;

    const file = new File([blob], item.displayName, { type: item.mimeType });
    if (
      typeof navigator !== 'undefined' &&
      navigator.share &&
      navigator.canShare &&
      navigator.canShare({ files: [file] })
    ) {
      try {
        await navigator.share({
          title: item.displayName,
          files: [file],
        });
        return;
      } catch {
        // Fall through to direct download
      }
    }

    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = item.displayName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return (
    <div className="min-h-screen bg-[var(--studio-bg)] text-[var(--studio-on-surface)]">
      {/* Top Bar */}
      <header className="sticky top-0 z-30 border-b border-[var(--studio-outline-variant)] bg-[var(--studio-bg)]/95 backdrop-blur-sm">
        <div className="mx-auto flex h-16 max-w-[1240px] items-center gap-3 px-4 sm:px-6">
          <button
            type="button"
            onClick={onBack}
            aria-label="Navigate back"
            className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--studio-on-surface)] transition-colors hover:bg-[var(--studio-surface-variant)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
          >
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="font-display text-xl font-bold tracking-tight text-[var(--studio-on-surface)]">
            Recorded Videos
          </h1>
        </div>
      </header>

      <main className="mx-auto max-w-[1240px] px-4 py-5 sm:px-6">
        {isLoading && recordings.length === 0 ? (
          <div className="flex min-h-[60vh] items-center justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-[var(--studio-outline)] border-t-[var(--studio-record-accent)]" />
          </div>
        ) : recordings.length === 0 ? (
          <div className="flex min-h-[60vh] flex-col items-center justify-center px-6 text-center">
            <div className="flex h-[72px] w-[72px] items-center justify-center rounded-[20px] bg-[var(--studio-surface-variant)] text-[var(--studio-on-surface-variant)]">
              <Film className="h-8 w-8" />
            </div>
            <h2 className="mt-4 font-display text-base font-semibold text-[var(--studio-on-surface)]">
              No recordings yet
            </h2>
            <p className="mt-1.5 max-w-sm text-sm text-[var(--studio-on-surface-variant)]">
              Captured videos will appear here ready to play or share.
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {recordings.map((item) => {
              const details = [
                item.width > 0 && item.height > 0 ? `${item.width}×${item.height}` : null,
                item.durationMs > 0 ? formatDuration(item.durationMs) : null,
                formatFileSize(item.sizeBytes),
                item.dateAddedSec > 0 ? formatDate(item.dateAddedSec) : null,
              ]
                .filter(Boolean)
                .join(' • ');

              return (
                <article
                  key={item.id}
                  className="flex items-center gap-3 rounded-2xl border border-[var(--studio-outline-variant)] bg-[var(--studio-surface)] p-3.5 transition-colors hover:border-[var(--studio-outline)] hover:bg-[var(--studio-surface-variant)]/45"
                >
                  <button
                    type="button"
                    onClick={() => setPlayingItem(item)}
                    className="flex min-w-0 flex-1 items-center gap-3 text-left focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
                  >
                    <div className="flex h-[52px] w-[52px] shrink-0 items-center justify-center rounded-xl bg-[var(--studio-record-container)] text-[var(--studio-record-accent)]">
                      <Film className="h-5 w-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-semibold text-[var(--studio-on-surface)]">
                        {item.displayName}
                      </p>
                      <p className="mt-1 truncate font-mono-tabular text-xs text-[var(--studio-on-surface-variant)]">
                        {details}
                      </p>
                    </div>
                  </button>

                  <div className="flex shrink-0 items-center gap-1">
                    <button
                      type="button"
                      onClick={() => setPlayingItem(item)}
                      aria-label={`Play ${item.displayName}`}
                      title="Play"
                      className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--studio-on-surface-variant)] transition-colors hover:bg-[var(--studio-surface-variant)] hover:text-[var(--studio-on-surface)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
                    >
                      <Play className="h-4.5 w-4.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleShareOrDownload(item)}
                      aria-label={`Share or download ${item.displayName}`}
                      title="Share / Download"
                      className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--studio-on-surface-variant)] transition-colors hover:bg-[var(--studio-surface-variant)] hover:text-[var(--studio-on-surface)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
                    >
                      <Share2 className="h-4.5 w-4.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setPendingDelete(item)}
                      aria-label={`Delete ${item.displayName}`}
                      title="Delete"
                      className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--studio-on-surface-variant)] transition-colors hover:bg-[var(--studio-error-container)] hover:text-[var(--studio-error)] focus-visible:outline-2 focus-visible:outline-[var(--studio-record-accent)]"
                    >
                      <Trash2 className="h-4.5 w-4.5" />
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </main>

      {playingItem && (
        <VideoPlayerDialog
          item={playingItem}
          onDismiss={() => setPlayingItem(null)}
        />
      )}

      {pendingDelete && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Delete recording confirmation"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-xs"
        >
          <div className="w-full max-w-sm rounded-2xl border border-[var(--studio-outline)] bg-[var(--studio-surface)] p-5 shadow-2xl">
            <h2 className="font-display text-lg font-semibold text-[var(--studio-on-surface)]">
              Delete recording?
            </h2>
            <p className="mt-2 text-sm text-[var(--studio-on-surface-variant)]">
              Remove {pendingDelete.displayName} permanently from browser storage?
            </p>
            <div className="mt-5 flex justify-end gap-2.5">
              <button
                type="button"
                onClick={() => setPendingDelete(null)}
                className="rounded-xl px-4 py-2 text-sm font-medium text-[var(--studio-on-surface-variant)] hover:bg-[var(--studio-surface-variant)] hover:text-[var(--studio-on-surface)]"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  onDelete(pendingDelete);
                  setPendingDelete(null);
                }}
                className="rounded-xl bg-[var(--studio-record-accent)] px-4 py-2 text-sm font-semibold text-white hover:opacity-95"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

interface VideoPlayerDialogProps {
  item: RecordedVideoItem;
  onDismiss: () => void;
}

function VideoPlayerDialog({ item, onDismiss }: VideoPlayerDialogProps) {
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    let active = true;
    let createdUrl: string | null = null;

    void getRecordingBlob(item.id).then((blob) => {
      if (!active) return;
      if (!blob) {
        setLoadError(true);
        return;
      }
      createdUrl = URL.createObjectURL(blob);
      setVideoUrl(createdUrl);
    });

    return () => {
      active = false;
      if (createdUrl) {
        URL.revokeObjectURL(createdUrl);
      }
    };
  }, [item.id]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Playing ${item.displayName}`}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-xs sm:p-7"
    >
      <div className="flex h-[85vh] w-full max-w-[1080px] flex-col overflow-hidden rounded-[20px] border border-[var(--studio-outline)] bg-[var(--studio-surface)] shadow-2xl">
        <div className="flex items-center justify-between gap-3 border-b border-[var(--studio-outline-variant)] px-4 py-2.5">
          <p className="truncate text-sm font-semibold text-[var(--studio-on-surface)]">
            {item.displayName}
          </p>
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Close video player"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-[var(--studio-on-surface-variant)] transition-colors hover:bg-[var(--studio-surface-variant)] hover:text-[var(--studio-on-surface)]"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex flex-1 items-center justify-center bg-black p-4">
          {loadError ? (
            <p className="text-sm text-[var(--studio-on-surface-variant)]">
              Unable to load recording video data.
            </p>
          ) : videoUrl ? (
            <video
              src={videoUrl}
              controls
              autoPlay
              playsInline
              className="max-h-full max-w-full rounded-lg"
            />
          ) : (
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-[var(--studio-record-accent)]" />
          )}
        </div>
      </div>
    </div>
  );
}
