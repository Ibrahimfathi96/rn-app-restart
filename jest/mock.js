/**
 * Official Jest mock. In your jest setup file:
 *
 *   jest.mock('rn-app-restart', () => require('rn-app-restart/jest/mock'));
 */
const restart = jest.fn();

module.exports = {
  __esModule: true,
  restart,
  default: { restart, Restart: restart },
};
