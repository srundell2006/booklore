export type WantedBookStatus = 'WANTED' | 'GRABBED' | 'IMPORTED' | 'FAILED';

export interface WantedBook {
  id: number;
  title: string;
  author?: string;
  isbn13?: string;
  asin?: string;
  status: WantedBookStatus;
  preferredFormat?: string;
  addedAt: string;
  lastSearchAt?: string;
  grabbedAt?: string;
  grabbedReleaseTitle?: string;
  grabbedIndexer?: string;
  downloadClient?: string;
  downloadId?: string;
  failureReason?: string;
  searchAttempts: number;
  importedBookId?: number;
}

export interface ProwlarrRelease {
  guid: string;
  title: string;
  indexer: string;
  size?: number;
  downloadUrl?: string;
  magnetUrl?: string;
  infoUrl?: string;
  protocol: 'usenet' | 'torrent';
  seeders?: number;
  leechers?: number;
  score?: number;
}

export interface BookAcquisitionSettings {
  enabled: boolean;
  prowlarrUrl?: string;
  prowlarrApiKey?: string;
  searchCategories: string;
  sabnzbdUrl?: string;
  sabnzbdApiKey?: string;
  sabnzbdCategory: string;
  qbittorrentUrl?: string;
  qbittorrentUsername?: string;
  qbittorrentPassword?: string;
  qbittorrentCategory: string;
  autoGrab: boolean;
  autoGrabMinScore: number;
  formatPriority: string;
  preferUsenet: boolean;
  maxSizeMb: number;
}

export interface ConnectionTestResult {
  prowlarr: boolean;
  sabnzbd: boolean;
  qbittorrent: boolean;
}

export interface BookLookupResult {
  title: string;
  authors?: string[];
  description?: string;
  publisher?: string;
  publishedYear?: number;
  isbn13?: string;
  isbn10?: string;
  asin?: string;
  thumbnailUrl?: string;
  provider?: string;
  inLibrary: boolean;
  existingBookId?: number;
  alreadyWanted: boolean;
}

export interface DownloadQueueItem {
  id: string;
  name: string;
  client: 'SABNZBD' | 'QBITTORRENT';
  state: string;
  progress: number;
  sizeBytes?: number;
  remainingBytes?: number;
  downloadSpeed?: number;
  etaSeconds?: number;
  category?: string;
  completed: boolean;
}
