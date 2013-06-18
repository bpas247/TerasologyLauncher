/*
 * Copyright (c) 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.launcher.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.launcher.LauncherSettings;
import org.terasology.launcher.util.DownloadException;
import org.terasology.launcher.util.DownloadUtils;
import org.terasology.launcher.util.JobResult;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class TerasologyGameVersions {

    private static final Logger logger = LoggerFactory.getLogger(TerasologyGameVersions.class);

    private static final String FILE_TERASOLOGY_JAR = "Terasology.jar";

    private static final int PREV_BUILD_NUMBERS_STABLE = 4;
    private static final int PREV_BUILD_NUMBERS_NIGHTLY = 4;
    private static final int MIN_BUILD_NUMBER_STABLE = 15; // URL changed between 14 and 15
    private static final int MIN_BUILD_NUMBER_NIGHTLY = 245;  // URL changed between 244 and 245
    private static final String GIT_BRANCH_STABLE = "master";
    private static final String GIT_BRANCH_NIGHTLY = "develop";

    private List<TerasologyGameVersion> gameVersionListStable;
    private List<TerasologyGameVersion> gameVersionListNightly;
    private SortedMap<Integer, TerasologyGameVersion> gameVersionMapStable;
    private SortedMap<Integer, TerasologyGameVersion> gameVersionMapNightly;

    public TerasologyGameVersions() {
    }

    public List<TerasologyGameVersion> getGameVersionList(final GameBuildType buildType) {
        if (GameBuildType.STABLE == buildType) {
            return gameVersionListStable;
        }
        return gameVersionListNightly;
    }

    public TerasologyGameVersion getGameVersionForBuildVersion(final GameBuildType buildType, final int buildVersion) {
        final List<TerasologyGameVersion> gameVersionList = getGameVersionList(buildType);
        for (TerasologyGameVersion gameVersion : gameVersionList) {
            if (buildVersion == gameVersion.getBuildVersion()) {
                return gameVersion;
            }
        }
        logger.warn("GameVersion not found for {} {}.", buildType, buildVersion);
        return null;
    }

    public synchronized void loadGameVersions(final LauncherSettings launcherSettings, final File gamesDirectory) {
        gameVersionMapStable = new TreeMap<Integer, TerasologyGameVersion>();
        gameVersionMapNightly = new TreeMap<Integer, TerasologyGameVersion>();
        final SortedSet<Integer> buildNumbersStable = new TreeSet<Integer>();
        final SortedSet<Integer> buildNumbersNightly = new TreeSet<Integer>();

        loadSettingsBuildNumber(launcherSettings, buildNumbersStable, GameBuildType.STABLE, MIN_BUILD_NUMBER_STABLE);
        loadSettingsBuildNumber(launcherSettings, buildNumbersNightly, GameBuildType.NIGHTLY, MIN_BUILD_NUMBER_NIGHTLY);

        final Integer lastBuildNumberStable = loadLastSuccessfulBuildNumber(buildNumbersStable,
            DownloadUtils.TERASOLOGY_STABLE_JOB_NAME, MIN_BUILD_NUMBER_STABLE, PREV_BUILD_NUMBERS_STABLE);
        final Integer lastBuildNumberNightly = loadLastSuccessfulBuildNumber(buildNumbersNightly,
            DownloadUtils.TERASOLOGY_NIGHTLY_JOB_NAME, MIN_BUILD_NUMBER_NIGHTLY, PREV_BUILD_NUMBERS_NIGHTLY);

        loadInstalledGames(gamesDirectory, buildNumbersStable, buildNumbersNightly);

        fillBuildNumbers(buildNumbersStable, MIN_BUILD_NUMBER_STABLE, lastBuildNumberStable);
        fillBuildNumbers(buildNumbersNightly, MIN_BUILD_NUMBER_NIGHTLY, lastBuildNumberNightly);

        loadGameVersions(buildNumbersStable, GameBuildType.STABLE,
            DownloadUtils.TERASOLOGY_STABLE_JOB_NAME, gameVersionMapStable);
        loadGameVersions(buildNumbersNightly, GameBuildType.NIGHTLY,
            DownloadUtils.TERASOLOGY_NIGHTLY_JOB_NAME, gameVersionMapNightly);

        gameVersionListStable = createList(lastBuildNumberStable, GameBuildType.STABLE, gameVersionMapStable);
        gameVersionListNightly = createList(lastBuildNumberNightly, GameBuildType.NIGHTLY, gameVersionMapNightly);
    }

    public synchronized void fixSettingsBuildVersion(final LauncherSettings launcherSettings) {
        if ((gameVersionMapStable != null) && (gameVersionMapNightly != null)) {
            fixSettingsBuildVersion(launcherSettings, GameBuildType.STABLE, gameVersionMapStable);
            fixSettingsBuildVersion(launcherSettings, GameBuildType.NIGHTLY, gameVersionMapNightly);
        }
    }

    private void loadSettingsBuildNumber(final LauncherSettings launcherSettings,
                                         final SortedSet<Integer> buildNumbers, final GameBuildType buildType,
                                         final int minBuildNumber) {
        final int buildVersion = launcherSettings.getBuildVersion(buildType);
        if ((buildVersion >= minBuildNumber) && (TerasologyGameVersion.BUILD_VERSION_LATEST != buildVersion)) {
            buildNumbers.add(buildVersion);
        }
    }

    private Integer loadLastSuccessfulBuildNumber(final SortedSet<Integer> buildNumbers, final String jobName,
                                                  final int minBuildNumber, final int prevBuildNumbers) {
        Integer lastSuccessfulBuildNumber = null;
        try {
            // Use "successful" and not "stable" for TerasologyGame.
            lastSuccessfulBuildNumber = DownloadUtils.loadLastSuccessfulBuildNumber(jobName);
            if (lastSuccessfulBuildNumber >= minBuildNumber) {
                buildNumbers.add(lastSuccessfulBuildNumber);
                // add previous build numbers
                buildNumbers.add(Math.max(minBuildNumber, lastSuccessfulBuildNumber - prevBuildNumbers));
            }
        } catch (DownloadException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Retrieving last successful build number failed. {}", jobName, e);
            } else {
                logger.warn("Retrieving last successful build number failed.");
            }
        }
        return lastSuccessfulBuildNumber;
    }

    private void loadInstalledGames(final File directory,
                                    final SortedSet<Integer> buildNumbersStable,
                                    final SortedSet<Integer> buildNumbersNightly) {
        final File[] gameJar = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(final File file) {
                return file.isFile() && file.canRead() && FILE_TERASOLOGY_JAR.equals(file.getName());
            }
        }
        );

        if ((gameJar != null) && (gameJar.length == 1)) {
            final TerasologyGameVersion gameVersion = loadInstalledGameVersion(gameJar[0]);
            if (gameVersion != null) {
                switch (gameVersion.getBuildType()) {
                    case STABLE:
                        if (gameVersion.getBuildNumber() >= MIN_BUILD_NUMBER_STABLE) {
                            buildNumbersStable.add(gameVersion.getBuildNumber());
                            if (!gameVersionMapStable.containsKey(gameVersion.getBuildNumber())) {
                                gameVersionMapStable.put(gameVersion.getBuildNumber(), gameVersion);
                            } else {
                                logger.debug("Installed game already loaded. {}", gameJar[0]);
                            }
                        }
                        break;
                    case NIGHTLY:
                        if (gameVersion.getBuildNumber() >= MIN_BUILD_NUMBER_NIGHTLY) {
                            buildNumbersNightly.add(gameVersion.getBuildNumber());
                            if (!gameVersionMapNightly.containsKey(gameVersion.getBuildNumber())) {
                                gameVersionMapNightly.put(gameVersion.getBuildNumber(), gameVersion);
                            } else {
                                logger.debug("Installed game already loaded. {}", gameJar[0]);
                            }
                        }
                        break;
                }
            }
        } else {
            final File[] subDirectories = directory.listFiles(new FileFilter() {

                @Override
                public boolean accept(final File file) {
                    return file.isDirectory() && file.canRead();
                }
            });
            if (subDirectories != null) {
                for (File subDirectory : subDirectories) {
                    loadInstalledGames(subDirectory, buildNumbersStable, buildNumbersNightly);
                }
            }
        }
    }

    private TerasologyGameVersion loadInstalledGameVersion(final File gameJar) {
        TerasologyGameVersion gameVersion = null;
        if (gameJar.exists() && gameJar.canRead() && gameJar.isFile()) {
            final TerasologyGameVersionInfo gameVersionInfo = TerasologyGameVersionInfo.loadFromJar(gameJar);
            GameBuildType installedBuildType = null;
            Integer installedBuildNumber = null;

            if ((gameVersionInfo.getJobName() != null) && (gameVersionInfo.getJobName().length() > 0)) {
                if (gameVersionInfo.getJobName().equals(DownloadUtils.TERASOLOGY_STABLE_JOB_NAME)) {
                    installedBuildType = GameBuildType.STABLE;
                } else if (gameVersionInfo.getJobName().equals(DownloadUtils.TERASOLOGY_NIGHTLY_JOB_NAME)) {
                    installedBuildType = GameBuildType.NIGHTLY;
                }
            }

            if ((installedBuildType == null)
                && (gameVersionInfo.getGitBranch() != null) && (gameVersionInfo.getGitBranch().length() > 0)) {
                if (gameVersionInfo.getGitBranch().equals(GIT_BRANCH_STABLE)) {
                    installedBuildType = GameBuildType.STABLE;
                } else if (gameVersionInfo.getGitBranch().equals(GIT_BRANCH_NIGHTLY)) {
                    installedBuildType = GameBuildType.NIGHTLY;
                }
            }

            if ((gameVersionInfo.getBuildNumber() != null) && (gameVersionInfo.getBuildNumber().length() > 0)) {
                try {
                    installedBuildNumber = Integer.parseInt(gameVersionInfo.getBuildNumber());
                } catch (NumberFormatException e) {
                    logger.error("Could not parse build number '{}'!", gameVersionInfo.getBuildNumber(), e);
                }
            }

            if ((installedBuildType != null) && (installedBuildNumber != null)) {
                gameVersion = new TerasologyGameVersion();
                gameVersion.setBuildType(installedBuildType);
                gameVersion.setBuildNumber(installedBuildNumber);
                gameVersion.setInstallationPath(gameJar.getParentFile());
                gameVersion.setGameJar(gameJar);
                gameVersion.setGameVersionInfo(gameVersionInfo);
            } else {
                logger.warn("Could not load version info from file {}.", gameJar);
            }
        }
        return gameVersion;
    }

    private void fillBuildNumbers(final SortedSet<Integer> buildNumbers, final int minBuildNumber,
                                  final Integer lastBuildNumber) {
        if ((buildNumbers != null) && !buildNumbers.isEmpty()) {
            int first = buildNumbers.first();
            if (first < minBuildNumber) {
                first = minBuildNumber;
            }
            int last = buildNumbers.last();
            if ((lastBuildNumber != null) && (last > lastBuildNumber)) {
                last = lastBuildNumber;
            }
            // Add all build numbers between first and last
            for (int buildNumber = first + 1; buildNumber < last; buildNumber++) {
                buildNumbers.add(buildNumber);
            }
        }
    }

    private void loadGameVersions(final SortedSet<Integer> buildNumbers, final GameBuildType buildType,
                                  final String jobName, final SortedMap<Integer, TerasologyGameVersion> gameVersions) {
        // TODO Create a cache -> faster loading and works without internet connection
        for (Integer buildNumber : buildNumbers) {
            final TerasologyGameVersion gameVersion;
            if (gameVersions.containsKey(buildNumber)) {
                gameVersion = gameVersions.get(buildNumber);
            } else {
                gameVersion = new TerasologyGameVersion();
                gameVersion.setBuildNumber(buildNumber);
                gameVersion.setBuildType(buildType);
                gameVersions.put(buildNumber, gameVersion);
            }

            // load and set successful
            boolean successful = false;
            try {
                JobResult jobResult = DownloadUtils.loadJobResult(jobName, buildNumber);
                successful = (jobResult != null
                    && ((jobResult == JobResult.SUCCESS) || (jobResult == JobResult.UNSTABLE)));
            } catch (DownloadException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Load job result failed. {}", jobName, e);
                } else {
                    logger.warn("Load job result failed.");
                }
            }
            gameVersion.setSuccessful(successful);

            // load and set changeLog
            List<String> changeLog = null;
            try {
                changeLog = DownloadUtils.loadChangeLog(jobName, buildNumber);
            } catch (DownloadException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Load change log failed. {}", jobName, e);
                } else {
                    logger.warn("Load change log failed.");
                }
            }
            if ((changeLog != null) && !changeLog.isEmpty()) {
                gameVersion.setChangeLog(Collections.unmodifiableList(changeLog));
            }

            // load and set gameVersionInfo
            TerasologyGameVersionInfo gameVersionInfo = null;
            try {
                gameVersionInfo = DownloadUtils.loadTerasologyGameVersionInfo(jobName, buildNumber);
            } catch (DownloadException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Load game version info failed. {}", jobName, e);
                } else {
                    logger.warn("Load game version info failed.");
                }
            }
            if (gameVersionInfo != null) {
                gameVersion.setGameVersionInfo(gameVersionInfo);
            }
        }
    }

    private List<TerasologyGameVersion> createList(final Integer lastBuildNumber, final GameBuildType buildType,
                                                   final SortedMap<Integer, TerasologyGameVersion> gameVersionMap) {
        final List<TerasologyGameVersion> gameVersionList = new ArrayList<TerasologyGameVersion>();
        gameVersionList.addAll(gameVersionMap.values());

        final TerasologyGameVersion latestGameVersion = new TerasologyGameVersion();
        latestGameVersion.setLatest(true);
        if ((lastBuildNumber != null) && gameVersionMap.containsKey(lastBuildNumber)) {
            gameVersionMap.get(lastBuildNumber).copyTo(latestGameVersion);
        } else {
            latestGameVersion.setBuildType(buildType);
            if (lastBuildNumber != null) {
                latestGameVersion.setBuildNumber(lastBuildNumber);
            }
        }
        gameVersionList.add(latestGameVersion);
        Collections.reverse(gameVersionList);

        return Collections.unmodifiableList(gameVersionList);
    }

    private void fixSettingsBuildVersion(final LauncherSettings launcherSettings, final GameBuildType buildType,
                                         final SortedMap<Integer, TerasologyGameVersion> gameVersionMap) {
        final int buildVersion = launcherSettings.getBuildVersion(buildType);
        if ((buildVersion != TerasologyGameVersion.BUILD_VERSION_LATEST) && !gameVersionMap.containsKey(buildVersion)) {
            Integer newBuildVersion = TerasologyGameVersion.BUILD_VERSION_LATEST;
            for (TerasologyGameVersion gameVersion : gameVersionMap.values()) {
                if (gameVersion.isInstalled()) {
                    newBuildVersion = gameVersion.getBuildNumber();
                    // no break => find highest installed version
                }
            }
            launcherSettings.setBuildVersion(newBuildVersion, buildType);
            // don't store settings
        }
    }

    public void updateGameVersionsAfterInstallation(final File terasologyDirectory) {
        final File gameJar = new File(terasologyDirectory, FILE_TERASOLOGY_JAR);
        final TerasologyGameVersion gameVersion = loadInstalledGameVersion(gameJar);
        if (gameVersion != null) {
            final List<TerasologyGameVersion> gameVersionList = getGameVersionList(gameVersion.getBuildType());
            for (TerasologyGameVersion currentGameVersion : gameVersionList) {
                if (gameVersion.getBuildNumber().equals(currentGameVersion.getBuildNumber())) {
                    if (gameVersion.getGameVersionInfo() != null) {
                        currentGameVersion.setGameVersionInfo(gameVersion.getGameVersionInfo());
                    }
                    currentGameVersion.setInstallationPath(gameVersion.getInstallationPath());
                    currentGameVersion.setGameJar(gameVersion.getGameJar());
                }
            }
        } else {
            logger.error("Could not load game version from directory. {}", terasologyDirectory);
        }
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[gameVersionListStable=" + gameVersionListStable
            + ", gameVersionListNightly=" + gameVersionListNightly + "]";
    }
}
