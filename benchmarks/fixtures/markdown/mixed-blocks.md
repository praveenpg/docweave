# Block HTML between Markdown

Intro paragraph before the block.

<div class="note">
<p>Block paragraph inside a div.</p>
<ul><li>HTML list one</li><li>HTML list two</li></ul>
</div>

## Section after block

- Markdown list item
- Second item

<h2>HTML heading</h2>

<p>HTML paragraph with <a href="https://example.org/h">a link</a>.</p>

<table>
<tr><th>Outer</th><th>Data</th></tr>
<tr><td>A</td><td><table><tr><td>inner 1</td></tr><tr><td>inner 2</td></tr></table></td></tr>
</table>

<details>
<summary>More detail</summary>

Markdown inside details after a blank line.

</details>

<script>window.tracking = true;</script>
<style>.note { color: red; }</style>
<!-- An HTML comment that should not become text. -->

<div>
Unclosed wrapper starts here.

Paragraph after the unclosed wrapper.

<pre>
  preformatted   text
</pre>

Final paragraph.
