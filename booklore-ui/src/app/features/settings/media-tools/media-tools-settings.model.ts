export interface EbookConversionSettings {
  enabled: boolean;
  serviceUrl?: string;
  defaultTargetFormat: string;
  attachAsAlternativeFormat: boolean;
  timeoutMinutes: number;
}

export interface AudiobookMergeSettings {
  enabled: boolean;
  serviceUrl?: string;
  stagingPath: string;
  serviceStagingPath: string;
  audioCodec: string;
  audioBitrate: string;
  audioSamplerate: string;
  audioChannels: string;
  maxChapterLength: string;
  useFilenamesAsChapters: boolean;
  preferLossless: boolean;
  jobs: number;
  deleteSourcesAfterMerge: boolean;
  jobTimeoutMinutes: number;
}

export interface AudiobookVerificationSettings {
  enabled: boolean;
  whisperUrl?: string;
  excerptSeconds: number;
  ollamaModel: string;
  ollamaUrl?: string;
}

export interface ComicDetectionSettings {
  autoMarkThreshold: number;
  reviewThreshold: number;
  structuralAnalysis: boolean;
  metadataHeuristics: boolean;
  llmTiebreaker: boolean;
  recheckExisting: boolean;
}

export interface IsbnScanSettings {
  spineItemsToScan: number;
}
