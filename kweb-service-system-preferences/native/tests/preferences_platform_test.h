#ifndef KWEB_PREFERENCES_PLATFORM_TEST_H_
#define KWEB_PREFERENCES_PLATFORM_TEST_H_

/** Starts the target's isolated test environment before any provider connects. */
void PreparePlatformEnvironment();

/** Releases the target's isolated test environment. */
void ReleasePlatformEnvironment();

/** Runs the real provider structure test of the current target. */
int RunPlatformNativeStructureTest();

#endif
