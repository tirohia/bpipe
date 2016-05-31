/*
 * Copyright (c) 2016 MCRI, authors
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package bpipe.cmd

import java.io.Writer;
import java.util.List

import bpipe.CommandManager;
import bpipe.Config
import groovy.util.logging.Log;;;

@Log
class Stop extends BpipeCommand {
    
    public Stop(List<String> args) {
        super("stop", args);
    }

    @Override
    public void run(PrintStream out) {
        Config.config["mode"] = "stopcommands"
        int count = new CommandManager().stopAll()
        out.println "Stopped $count commands"
        
        // Find the pid of the running Bpipe instance
        String pid = new File(".bpipe/run.pid").text.trim()
        
        // Try to kill the bpipe process (ourselves!)
        String output = ("kill $pid").execute().text
        
        println "Output from kill: $output"
    }
}