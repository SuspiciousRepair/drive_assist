---
title: Development journal
permalink: /journal/
---

# Development journal

Notes from building and using Drive Assist on the car: design decisions, vehicle
findings, and the small pieces of work that make the dashboard more useful.

<ul class="journal-list">
{% for post in site.posts %}
  <li>
    <time datetime="{{ post.date | date_to_xmlschema }}">{{ post.date | date: "%B %-d, %Y" }}</time>
    <h2><a href="{{ post.url | relative_url }}">{{ post.title }}</a></h2>
    {% if post.description %}<p>{{ post.description }}</p>{% endif %}
  </li>
{% endfor %}
</ul>
