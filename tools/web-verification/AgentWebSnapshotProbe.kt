package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Emits the exact production JavaScript for an external Node semantic probe. */
object AgentWebSnapshotProbe {
    @JvmStatic fun main(args: Array<String>) {
        println(buildJsonArray {
            fun probe(name:String,op:String="read",start:Int=0,count:Int=80,query:String="",elementStart:Int=0,elementCount:Int=30) {
                add(buildJsonObject { put("name",name); put("script",AgentBrowserSnapshot.script(op,start,count,query,elementStart,elementCount)) })
            }
            probe("read",count=2,elementCount=2)
            probe("continue",start=2,count=2,elementStart=2,elementCount=2)
            probe("find","find",count=1,query="NEEDLE")
            probe("find_continue","find",start=3,count=1,query="needle")
            probe("links","links",count=1)
            probe("links_continue","links",start=1,count=1)
            probe("injection","find",query="\"); globalThis.injected=true; //")
            probe("large",count=200,elementCount=40)
            probe("last_lines",start=99_999,count=200)
            fun interaction(name:String,op:String="type",target:String,text:String?=null) {
                add(buildJsonObject { put("name",name); put("script",AgentBrowserInteraction.script(op,target,text,false)) })
            }
            interaction("interaction_select",target="#language",text="go")
            interaction("interaction_select_invalid",target="#language",text="missing-option")
            interaction("interaction_known_element",target="#stable",text="stable-target")
            interaction("interaction_textarea",target="#notes",text="A multiline\nplain text value")
            interaction("interaction_input_data",target="#normal",text="\"); globalThis.injected=true; //")
            interaction("interaction_password",target="#password",text="blocked")
            interaction("interaction_file",target="#file",text="/private/local/path")
            interaction("interaction_target_data",target="#missing'); globalThis.targetInjected=true; //",text="plain")
            interaction("interaction_click",op="click",target="#button")
        })
    }
}
