package com.example.myapplication.frame;

public class FrameCorners {
    private FramePoint topleft;
    private FramePoint bottomleft;
    private FramePoint topright;
    private FramePoint bottomright;

    public FrameCorners(FramePoint topleft,FramePoint bottomleft,FramePoint topright,FramePoint bottomright){
        this.topleft = topleft;
        this.bottomleft = bottomleft;
        this.topright = topright;
        this.bottomright = bottomright;
    }

    public FramePoint getTopleft(){
        return topleft;
    }

    public FramePoint getBottomleft(){
        return bottomleft;
    }

    public FramePoint getTopright(){
        return topright;
    }

    public FramePoint getBottomright(){
        return bottomright;
    }

}
