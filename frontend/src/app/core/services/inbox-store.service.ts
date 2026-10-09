import { Injectable, computed, inject, signal } from '@angular/core';
import { MailStatusDto, MessageSummaryDto } from '../models/message.model';
import { reviewState } from '../ui-meta';
import { MessageApiService } from './message-api.service';

/** How often the queue silently re-fetches, so emails picked up by the mailbox poller appear on their own. */
const AUTO_REFRESH_MS = 10_000;

/**
 * Single source of truth for the message list and mailbox status, shared by the shell (nav badges,
 * mailbox indicator), the review queue and the literature page, so they poll the backend once.
 */
@Injectable({ providedIn: 'root' })
export class InboxStore {
  private api = inject(MessageApiService);
  private timer?: ReturnType<typeof setInterval>;

  readonly messages = signal<MessageSummaryDto[]>([]);
  readonly mail = signal<MailStatusDto | null>(null);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);
  readonly lastRefresh = signal<Date | null>(null);

  readonly mailMessages = computed(() => this.messages().filter((m) => m.sourceType !== 'UPLOAD'));
  readonly literatureMessages = computed(() => this.messages().filter((m) => m.sourceType === 'UPLOAD'));
  readonly pendingMail = computed(() => this.mailMessages().filter((m) => reviewState(m) === 'PENDING').length);
  readonly pendingLiterature = computed(
    () => this.literatureMessages().filter((m) => reviewState(m) === 'PENDING').length,
  );
  readonly inFlight = computed(
    () => this.messages().filter((m) => m.status !== 'PROCESSED' && m.status !== 'FAILED').length,
  );

  start(): void {
    if (this.timer) return;
    this.refresh();
    this.timer = setInterval(() => this.refresh(true), AUTO_REFRESH_MS);
  }

  refresh(silent = false): void {
    if (!silent) this.loading.set(true);
    this.api.listQueue().subscribe({
      next: (data) => {
        this.messages.set(data);
        this.loading.set(false);
        this.loaded.set(true);
        this.error.set(null);
        this.lastRefresh.set(new Date());
      },
      error: (err) => {
        this.loading.set(false);
        this.loaded.set(true);
        this.error.set(err?.status === 0 ? 'Backend unreachable' : 'Failed to load message queue');
      },
    });
    this.api.getMailStatus().subscribe({
      next: (status) => this.mail.set(status),
      error: () => this.mail.set(null),
    });
  }
}
