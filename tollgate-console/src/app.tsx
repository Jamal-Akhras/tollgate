import { signedIn } from './api/client';
import { Activity } from './pages/Activity/Activity';
import { Audit } from './pages/Audit/Audit';
import { Batch } from './pages/Audit/Batch';
import { AuditRecord } from './pages/Audit/Record';
import { Config } from './pages/Config/Config';
import { Flow } from './pages/Flow/Flow';
import { Metrics } from './pages/Metrics/Metrics';
import { SignIn } from './pages/SignIn/SignIn';
import { Usage } from './pages/Usage/Usage';
import { route } from './router';
import { Shell } from './ui/Shell';

function page() {
  switch (route.value.name) {
    case 'flow':
    case 'sign-in':
      return <Flow />;
    case 'activity':
      return <Activity />;
    case 'audit':
      return <Audit />;
    case 'batch':
      return <Batch />;
    case 'record':
      return <AuditRecord />;
    case 'metrics':
      return <Metrics />;
    case 'usage':
      return <Usage />;
    case 'config':
      return <Config />;
  }
}

export function App() {
  if (!signedIn.value) {
    return <SignIn />;
  }
  return <Shell>{page()}</Shell>;
}
