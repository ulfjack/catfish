"""Simple rule to create a runnable shell script with data dependencies."""

def _sh_runner_impl(ctx):
    script = ctx.actions.declare_file(ctx.label.name + "_runner.sh")
    ctx.actions.write(
        output = script,
        content = ctx.attr.script,
        is_executable = True,
    )
    runfiles = ctx.runfiles(files = ctx.files.data).merge_all([
        dep[DefaultInfo].default_runfiles
        for dep in ctx.attr.data
    ])
    return [DefaultInfo(
        executable = script,
        runfiles = runfiles,
    )]

sh_runner = rule(
    implementation = _sh_runner_impl,
    attrs = {
        "script": attr.string(mandatory = True),
        "data": attr.label_list(allow_files = True),
    },
    executable = True,
)
