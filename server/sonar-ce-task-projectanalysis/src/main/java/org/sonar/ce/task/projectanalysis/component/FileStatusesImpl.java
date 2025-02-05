/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.task.projectanalysis.component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.ce.task.projectanalysis.analysis.AnalysisMetadataHolder;
import org.sonar.ce.task.projectanalysis.source.SourceHashRepository;
import org.sonar.db.source.FileHashesDto;

import static com.google.common.base.Preconditions.checkState;
import static org.sonar.ce.task.projectanalysis.component.ComponentVisitor.Order.PRE_ORDER;

public class FileStatusesImpl implements FileStatuses {
  private static final Logger LOG = Loggers.get(FileStatusesImpl.class);

  private final PreviousSourceHashRepository previousSourceHashRepository;
  private final SourceHashRepository sourceHashRepository;
  private final AnalysisMetadataHolder analysisMetadataHolder;
  private final TreeRootHolder treeRootHolder;
  private Set<String> fileUuidsMarkedAsUnchanged;
  private int notMarkedAsUnchanged = 0;

  public FileStatusesImpl(AnalysisMetadataHolder analysisMetadataHolder, TreeRootHolder treeRootHolder, PreviousSourceHashRepository previousSourceHashRepository,
    SourceHashRepository sourceHashRepository) {
    this.analysisMetadataHolder = analysisMetadataHolder;
    this.treeRootHolder = treeRootHolder;
    this.previousSourceHashRepository = previousSourceHashRepository;
    this.sourceHashRepository = sourceHashRepository;
  }

  public void initialize() {
    fileUuidsMarkedAsUnchanged = new HashSet<>();
    if (!analysisMetadataHolder.isPullRequest() && !analysisMetadataHolder.isFirstAnalysis()) {
      new DepthTraversalTypeAwareCrawler(new Visitor()).visit(treeRootHolder.getRoot());
    }
    LOG.warn("FILES MARKED AS UNCHANGED: " + fileUuidsMarkedAsUnchanged.size());
    LOG.warn("FILES NOT MARKED AS UNCHANGED: " + notMarkedAsUnchanged);
  }

  private class Visitor extends TypeAwareVisitorAdapter {
    private boolean canTrustUnchangedFlags = true;

    private Visitor() {
      super(CrawlerDepthLimit.FILE, PRE_ORDER);
    }

    @Override
    public void visitFile(Component file) {
      if (file.getStatus() != Component.Status.SAME || !canTrustUnchangedFlags) {
        return;
      }

      canTrustUnchangedFlags = hashEquals(file);
      if (canTrustUnchangedFlags) {
        if (file.getFileAttributes().isMarkedAsUnchanged()) {
          fileUuidsMarkedAsUnchanged.add(file.getUuid());
        } else {
          notMarkedAsUnchanged++;
        }
      } else {
        LOG.error("FILE HAS DIFFERENT HASH: " + file.getName());
        fileUuidsMarkedAsUnchanged.clear();
      }
    }
  }

  @Override
  public boolean isUnchanged(Component component) {
    failIfNotInitialized();
    return component.getStatus() == Component.Status.SAME && hashEquals(component);
  }

  @Override
  public boolean isDataUnchanged(Component component) {
    failIfNotInitialized();
    return fileUuidsMarkedAsUnchanged.contains(component.getUuid());
  }

  private boolean hashEquals(Component component) {
    Optional<String> dbHash = previousSourceHashRepository.getDbFile(component).map(FileHashesDto::getSrcHash);
    return dbHash.map(hash -> hash.equals(sourceHashRepository.getRawSourceHash(component))).orElse(false);
  }

  private void failIfNotInitialized() {
    checkState(fileUuidsMarkedAsUnchanged != null, "Not initialized");
  }
}
