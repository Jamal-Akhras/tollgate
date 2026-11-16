import { signedIn } from './api/client';
import { Config } from './pages/Config/Config';
import { Flow } from './pages/Flow/Flow';
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

function page() {
  switch (route.value.name) {
    case 'flow':
    case 'sign-in':
      return <Flow />;
    case 'config':
      return <Config />;
    default:
      return <h1>{titles[route.value.name]}</h1>;
  }
}

export function App() {
  if (!signedIn.value) {
    return <SignIn />;
  }
  return <Shell>{page()}</Shell>;
}
