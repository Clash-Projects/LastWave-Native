'use strict';
/**
 * Backward-compat shim — new code should import from src/ui/* directly:
 *   const { esc } = require('./ui/esc');
 *   const { ICON, FAVICON } = require('./ui/icons');
 *   const { layout, publicShell, ASSET_VERSION } = require('./ui/layout');
 *   const { pill, emptyState } = require('./ui/components');
 * This file re-exports the split modules so existing routes keep working.
 */
const { esc } = require('./ui/esc');
const { ICON, FAVICON } = require('./ui/icons');
const { layout } = require('./ui/layout');
const { pill, emptyState } = require('./ui/components');

module.exports = { esc, layout, pill, emptyState, ICON, FAVICON };
