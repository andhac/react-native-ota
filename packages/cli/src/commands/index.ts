import type { Command } from 'commander';

import { bundleCommand } from './bundle.js';
import { doctorCommand } from './doctor.js';
import { keysCommand } from './keys.js';
import { promoteCommand } from './promote.js';
import { publishCommand } from './publish.js';
import { rollbackCommand } from './rollback.js';
import { rolloutCommand } from './rollout.js';
import { signCommand } from './sign.js';

export function registerCommands(program: Command): void {
  program.command('bundle').description('Build a Hermes OTA package').action(bundleCommand);
  program.command('sign').description('Sign an OTA package').action(signCommand);
  program.command('publish').description('Publish a release').action(publishCommand);
  program.command('promote').description('Promote across channels').action(promoteCommand);
  program.command('rollout').description('Adjust rollout percent').action(rolloutCommand);
  program.command('rollback').description('Roll back a channel').action(rollbackCommand);
  program.command('doctor').description('Verify a package locally').action(doctorCommand);
  program.command('keys').description('Manage signing keys').action(keysCommand);
}
