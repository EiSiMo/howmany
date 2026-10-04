# how many?

Counts the objects in a photo, entirely on the phone, from a few marked instances as examples.

## Language

**Photo**:
The image the user takes or picks, in its own pixels. The app counts in it.
_Avoid_: image (that is the model's input after scaling and padding).

**Crop**:
The part of the photo the user counts in. It starts as the whole photo, can be shrunk, and grows back.

**Counted area**:
The crop as counting started. The crop can never grow beyond it.

**Exemplar**:
An object the user marks with a box as an example of what to count; there are up to three, and the model then finds every object that looks like them.
_Avoid_: sample, example, "the marked object". A box is the drawn frame or the gesture, not the exemplar itself.

**Point**:
One object in the count. The user removes wrong points and adds missed ones.
_Avoid_: dot, mark.

**Count**:
How many points there are.

**Detection**:
One object the model found while counting, before the user corrects it; it carries a confidence.
_Avoid_: hit.

**Heatmap**:
Where the model saw objects while counting.

## Example dialogue

- **Dev**: The user drags a box, so we have an exemplar?
- **Domain expert**: The drag draws a box. What it frames is an exemplar. Up to three of them together say what to count.
- **Dev**: And every detection becomes a point?
- **Domain expert**: Only once the user leaves it in the count. A detection is what the model found; a point is a counted object.
- **Dev**: What if the user shrinks the crop so an exemplar falls outside?
- **Domain expert**: They can't. The crop always keeps every exemplar.
