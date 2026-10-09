import { Category, MessageSummaryDto, PdfFlavor } from './models/message.model';

export interface CategoryMeta {
  short: string;
  label: string;
  icon: string;
  cssClass: string;
  hint: string;
}

/** Display metadata for the four buckets defined in Section 2 of the brief. */
export const CATEGORY_META: Record<Category, CategoryMeta> = {
  ICSR: {
    short: 'ICSR',
    label: 'Safety Report',
    icon: 'personal_injury',
    cssClass: 'icsr',
    hint: 'A patient had a bad reaction to a drug (patient + reporter + drug + bad outcome).',
  },
  PQC: {
    short: 'PQC',
    label: 'Quality Complaint',
    icon: 'inventory_2',
    cssClass: 'pqc',
    hint: 'Something is physically wrong with the product (seal, colour, contamination, packaging, counterfeit).',
  },
  MI: {
    short: 'MI',
    label: 'Info Request',
    icon: 'help_outline',
    cssClass: 'mi',
    hint: 'A question about a product (dosing, administration, interactions) with no reaction and no defect.',
  },
  NOT_RELEVANT: {
    short: 'N/R',
    label: 'Not Relevant',
    icon: 'block',
    cssClass: 'nr',
    hint: 'Marketing, spam, internal admin chatter.',
  },
};

export const CATEGORIES: Category[] = ['ICSR', 'PQC', 'MI', 'NOT_RELEVANT'];

export const PDF_FLAVOR_META: Record<PdfFlavor, { label: string; icon: string; hint: string }> = {
  DIGITAL: { label: 'Digital PDF', icon: 'description', hint: 'Text extracted directly from the text layer.' },
  SCANNED_HANDWRITTEN: {
    label: 'Scanned / handwritten',
    icon: 'draw',
    hint: 'Read page-by-page with the vision model; values carry an OCR confidence.',
  },
  ARTICLE: {
    label: 'Published article',
    icon: 'article',
    hint: 'Multi-column layout handled; only patient-case passages kept, references dropped.',
  },
  NON_ENGLISH: {
    label: 'Non-English',
    icon: 'translate',
    hint: 'Language detected and translated to English for analysis; original PDF shown here.',
  },
};

export const FIELD_GROUP_META: Record<string, { label: string; icon: string; order: number }> = {
  PATIENT: { label: 'Patient', icon: 'person', order: 1 },
  REPORTER: { label: 'Reporter', icon: 'record_voice_over', order: 2 },
  PRODUCT: { label: 'Product', icon: 'medication', order: 3 },
  REACTION: { label: 'Reaction', icon: 'coronavirus', order: 4 },
  SEVERITY: { label: 'Severity', icon: 'emergency', order: 5 },
  NARRATIVE: { label: 'Case narrative', icon: 'notes', order: 6 },
  PQC: { label: 'Quality complaint details', icon: 'inventory_2', order: 7 },
  MI: { label: 'Information request details', icon: 'help_outline', order: 8 },
};

export function groupMeta(group: string) {
  return FIELD_GROUP_META[group] ?? { label: humanize(group), icon: 'label', order: 99 };
}

/** "reporter_name_or_role" -> "Reporter name or role" */
export function humanize(value: string | null | undefined): string {
  if (!value) return '';
  const s = value.replace(/[_-]+/g, ' ').trim().toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

const NOT_STATED = /^(not stated|unknown|n\/a|none stated|not mentioned)$/i;

export function isNotStated(value: string | null | undefined): boolean {
  return !value || NOT_STATED.test(value.trim());
}

/** Confidence bands used consistently across the UI. */
export function confidenceBand(c: number | null | undefined): 'high' | 'medium' | 'low' | 'none' {
  if (c == null) return 'none';
  if (c >= 0.85) return 'high';
  if (c >= 0.6) return 'medium';
  return 'low';
}

export type ReviewState = 'PENDING' | 'REVIEWED' | 'NONE';

/** Whether a reviewer has signed off the message (all AI decisions reviewed). */
export function reviewState(m: Pick<MessageSummaryDto, 'classifications' | 'literatureCases'>): ReviewState {
  const items = [...m.classifications, ...m.literatureCases];
  if (!items.length) return 'NONE';
  return items.some((i) => i.reviewerStatus === 'PENDING') ? 'PENDING' : 'REVIEWED';
}

/** Category a reviewer settled on (override wins), or the AI's own if not reviewed. */
export function effectiveCategory(c: { category: Category; reviewerStatus: string; reviewerCategory: Category | null }) {
  return c.reviewerStatus === 'OVERRIDDEN' && c.reviewerCategory ? c.reviewerCategory : c.category;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}
