import { useState } from 'react';
import { useRecorder } from './hooks/useRecorder';
import { MainScreen } from './components/MainScreen';
import { RecordingsScreen } from './components/RecordingsScreen';
import { RegionSelectorOverlay } from './components/RegionSelectorOverlay';

type Route = 'main' | 'recordings';

export function App() {
  const [route, setRoute] = useState<Route>('main');
  const recorder = useRecorder();

  return (
    <>
      {route === 'main' ? (
        <MainScreen
          settings={recorder.settings}
          session={recorder.session}
          canvasWidthPx={recorder.canvasWidthPx}
          canvasHeightPx={recorder.canvasHeightPx}
          selectedPixelRegion={recorder.selectedPixelRegion}
          recordingsCount={recorder.recordings.length}
          liveSourceVideo={recorder.liveSourceVideo}
          onStartRecordClick={() => void recorder.startRecording()}
          onPauseResumeClick={recorder.togglePauseResume}
          onStopClick={recorder.stopRecording}
          onOpenRecordings={() => {
            void recorder.refreshRecordings();
            setRoute('recordings');
          }}
          onSelectMode={recorder.setRecordingMode}
          onSelectOrientationMode={recorder.setOrientationMode}
          onSelectAudioSource={recorder.setAudioSource}
          onSelectColorMode={recorder.setColorMode}
          onSelectBitrate={recorder.setVideoBitrate}
          onSelectRegionAnimation={recorder.setRegionAnimation}
          onEditRegion={recorder.openRegionEditor}
          onDismissMessages={recorder.clearMessages}
        />
      ) : (
        <RecordingsScreen
          recordings={recorder.recordings}
          isLoading={recorder.isLoadingRecordings}
          onBack={() => setRoute('main')}
          onDelete={(item) => void recorder.deleteRecording(item)}
        />
      )}

      {recorder.isEditingRegion && (
        <RegionSelectorOverlay
          initialRegion={recorder.settings.selectedRegion}
          screenWidthPx={recorder.canvasWidthPx}
          screenHeightPx={recorder.canvasHeightPx}
          regionAnimation={recorder.settings.regionAnimation}
          onConfirm={recorder.saveSelectedRegion}
          onCancel={recorder.closeRegionEditor}
        />
      )}
    </>
  );
}

export default App;
