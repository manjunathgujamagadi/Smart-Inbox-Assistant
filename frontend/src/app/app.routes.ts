import { Routes } from '@angular/router';
import { QueueListComponent } from './features/queue/queue-list.component';
import { MessageDetailComponent } from './features/review/message-detail.component';

export const routes: Routes = [
  { path: '', component: QueueListComponent },
  { path: 'messages/:id', component: MessageDetailComponent },
  { path: '**', redirectTo: '' },
];
