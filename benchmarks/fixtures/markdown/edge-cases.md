# Edge cases

#

## ![Logo only](logo.png)

Nested ***bold italic*** and **outer *inner* tail** and _under_ __score__.

Code keeps `*literal* markers` and \*escaped\* text; snake_case_name stays.

A [**bold link**](https://example.org/a) and *see [the docs](https://example.org/b)*.

Reference image ![chart][chart-ref] inline.

- 
- Item after an empty item
- Item with code:

  ```python
  print("in list")
  ```
- Lazy continuation
line joins the item.

| Key | `a|b` | Note |
| --- | --- | --- |
| not | a | table |

| Key | `a\|b` | Note |
| --- | --- | --- |
| *x* | `c\|d` |
| y | z | **w** | extra |

Unicode: café, naïve, 𝔘𝔫𝔦𝔠𝔬𝔡𝔢, 🧵 *🧵 thread* end.

[chart-ref]: https://example.org/chart.png
