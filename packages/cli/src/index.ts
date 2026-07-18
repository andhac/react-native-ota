#!/usr/bin/env node
import { Command } from 'commander';

import { registerCommands } from './commands/index.js';

const program = new Command();

program
  .name('ota')
  .description('React Native OTA CLI (scaffold — commands not implemented yet)')
  .version('0.0.0');

registerCommands(program);

program.parse();
