import { BreakpointObserver } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { InboxStore } from './core/services/inbox-store.service';
import { ReviewerService } from './core/services/reviewer.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  readonly store = inject(InboxStore);
  readonly reviewer = inject(ReviewerService);
  private router = inject(Router);

  readonly isMobile = signal(false);
  readonly navOpen = signal(true);
  editingName = false;
  nameDraft = '';

  constructor() {
    inject(BreakpointObserver)
      .observe('(max-width: 960px)')
      .subscribe((r) => {
        this.isMobile.set(r.matches);
        this.navOpen.set(!r.matches);
      });
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      if (this.isMobile()) this.navOpen.set(false);
    });
  }

  ngOnInit(): void {
    this.store.start();
  }

  startEditName(): void {
    this.nameDraft = this.reviewer.name();
    this.editingName = true;
  }

  saveName(): void {
    this.reviewer.setName(this.nameDraft);
    this.editingName = false;
  }

  mailState(): 'ok' | 'err' | 'off' {
    const m = this.store.mail();
    if (!m || !m.configured) return 'off';
    return m.lastError ? 'err' : 'ok';
  }
}
