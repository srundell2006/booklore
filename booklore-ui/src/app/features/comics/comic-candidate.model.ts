export interface ComicCandidate {
  id: number;
  bookId: number;
  title: string;
  authors: string;
  publisher?: string;
  fileName?: string;
  fileType?: string;
  score: number;
  verdict: string;
  status: string;
  signals: string[];
  detectedAt: string;
}

export type ComicScopeType = 'LIBRARY' | 'MAGIC_SHELF' | 'BOOKS';

export interface ComicDetectionRequest {
  refreshType: ComicScopeType;
  libraryId?: number;
  magicShelfId?: number;
  bookIds?: number[];
  recheckExisting?: boolean;
  dryRun?: boolean;
}

export interface ComicDetectionSettings {
  autoMarkThreshold: number;
  reviewThreshold: number;
  structuralAnalysis: boolean;
  metadataHeuristics: boolean;
  llmTiebreaker: boolean;
  recheckExisting: boolean;
}
