import { signedIn } from './api/client';
import { SignIn } from './pages/SignIn/SignIn';
import { route, type RouteName } from './router';
import { Shell } from './ui/Shell';

const titles: Record<RouteName, string> = {
  flow: 'Flow',
  activity: 'Activity',
  metrics: 'Metrics',
  usage: 'Usage',
  audit: 'Audit',
  batch: 'Audit batch',
  record: 'Audit record',
  config: 'Config',
  'sign-in': 'Flow',
};

export function App() {
  if (!signedIn.value) {
    return <SignIn />;
  }
  return (
    <Shell>
      <h1>{titles[route.value.name]}</h1>
    </Shell>
  );
}
