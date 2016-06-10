/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
package org.sonar.server.project.ws;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentLinkDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.measure.MeasureDto;

import static com.google.common.collect.ImmutableList.copyOf;
import static java.util.Objects.requireNonNull;
import static org.sonar.api.utils.DateUtils.formatDateTime;

class SearchMyProjectsData {
  private final List<ComponentDto> projects;
  private final ListMultimap<String, ComponentLinkDto> projectLinksByProjectUuid;
  private final Map<Long, String> lastAnalysisDates;
  private final Map<Long, String> qualityGateStatuses;

  private SearchMyProjectsData(Builder builder) {
    this.projects = copyOf(builder.projects);
    this.projectLinksByProjectUuid = buildProjectLinks(builder.projectLinks);
    this.lastAnalysisDates = buildAnalysisDates(builder.snapshots);
    this.qualityGateStatuses = buildQualityGateStatuses(builder.snapshots, builder.qualityGates);
  }

  static Builder builder() {
    return new Builder();
  }

  List<ComponentDto> projects() {
    return projects;
  }

  List<ComponentLinkDto> projectLinksFor(String projectUuid) {
    return projectLinksByProjectUuid.get(projectUuid);
  }

  Optional<String> lastAnalysisDateFor(long componentId) {
    return Optional.ofNullable(lastAnalysisDates.get(componentId));
  }

  Optional<String> qualityGateStatusFor(long componentId) {
    return Optional.ofNullable(qualityGateStatuses.get(componentId));
  }

  private static ListMultimap<String, ComponentLinkDto> buildProjectLinks(List<ComponentLinkDto> dtos) {
    ImmutableListMultimap.Builder<String, ComponentLinkDto> projectLinks = ImmutableListMultimap.builder();
    dtos.forEach(projectLink -> projectLinks.put(projectLink.getComponentUuid(), projectLink));
    return projectLinks.build();
  }

  private static Map<Long, String> buildAnalysisDates(List<SnapshotDto> snapshotDtos) {
    return ImmutableMap.copyOf(snapshotDtos.stream().collect(Collectors.toMap(
      SnapshotDto::getComponentId,
      snapshot -> formatDateTime(snapshot.getCreatedAt()))));
  }

  private static Map<Long, String> buildQualityGateStatuses(List<SnapshotDto> snapshots, List<MeasureDto> measures) {
    Map<Long, Long> componentIdsBySnapshotId = snapshots.stream().collect(Collectors.toMap(SnapshotDto::getId, SnapshotDto::getComponentId));
    return ImmutableMap.copyOf(measures.stream()
      .collect(Collectors.toMap(measure -> componentIdsBySnapshotId.get(measure.getSnapshotId()), MeasureDto::getData)));
  }

  static class Builder {
    private List<ComponentDto> projects;
    private List<ComponentLinkDto> projectLinks;
    private List<SnapshotDto> snapshots;
    private List<MeasureDto> qualityGates;

    private Builder() {
      // enforce method constructor
    }

    Builder setProjects(List<ComponentDto> projects) {
      this.projects = projects;
      return this;
    }

    public Builder setProjectLinks(List<ComponentLinkDto> projectLinks) {
      this.projectLinks = projectLinks;
      return this;
    }

    public Builder setSnapshots(List<SnapshotDto> snapshots) {
      this.snapshots = snapshots;
      return this;
    }

    public Builder setQualityGates(List<MeasureDto> qGateStatuses) {
      this.qualityGates = qGateStatuses;
      return this;
    }

    SearchMyProjectsData build() {
      requireNonNull(projects != null);
      requireNonNull(projectLinks != null);
      requireNonNull(snapshots != null);
      requireNonNull(qualityGates != null);

      return new SearchMyProjectsData(this);
    }
  }
}
