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
import React from 'react';
import sortBy from 'lodash/sortBy';
import Projects from './Projects';
import { getMyProjects } from '../../../api/components';

export default class ProjectsContainer extends React.Component {
  state = { loading: true };

  componentWillMount () {
    this.loadMore = this.loadMore.bind(this);
    document.querySelector('html').classList.add('dashboard-page');
  }

  componentDidMount () {
    this.mounted = true;
    this.loadProjects();
  }

  componentWillUnmount () {
    this.mounted = false;
    document.querySelector('html').classList.remove('dashboard-page');
  }

  loadProjects () {
    this.setState({ loading: true });
    return getMyProjects().then(r => {
      const projects = sortBy(r.projects, 'name');
      this.setState({
        projects,
        loading: false
      });
    });
  }

  loadMore () {
    return this.loadProjects(this.state.page + 1);
  }

  render () {
    if (this.state.projects == null) {
      return (
          <div className="text-center">
            <i className="spinner spinner-margin"/>
          </div>
      );
    }

    return (
        <Projects projects={this.state.projects}/>
    );
  }
}
